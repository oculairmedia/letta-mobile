#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

#define MAX_BODY (2 * 1024 * 1024)
#define BUFFER_SIZE 4096

struct header {
  char *value;
  struct header *next;
};

static void usage(void) {
  fprintf(stderr, "Usage: curl [-sS] [-I] [-X METHOD] [-H HEADER] [-d DATA] [-o FILE] URL\n");
}

static char *dupstr(const char *s) {
  size_t n = strlen(s) + 1;
  char *out = malloc(n);
  if (out) memcpy(out, s, n);
  return out;
}

static void append_json_string(FILE *out, const char *s) {
  fputc('"', out);
  for (const unsigned char *p = (const unsigned char *)s; *p; ++p) {
    switch (*p) {
      case '\\': fputs("\\\\", out); break;
      case '"': fputs("\\\"", out); break;
      case '\n': fputs("\\n", out); break;
      case '\r': fputs("\\r", out); break;
      case '\t': fputs("\\t", out); break;
      default:
        if (*p < 0x20) fprintf(out, "\\u%04x", *p);
        else fputc(*p, out);
    }
  }
  fputc('"', out);
}

static char b64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static unsigned char *base64_decode(const char *input, size_t *out_len) {
  size_t len = strlen(input);
  unsigned char *out = malloc((len / 4) * 3 + 3);
  if (!out) return NULL;
  int val = 0;
  int valb = -8;
  size_t pos = 0;
  for (size_t i = 0; i < len; i++) {
    unsigned char c = (unsigned char)input[i];
    if (c == '=') break;
    char *found = strchr(b64_table, c);
    if (!found) continue;
    val = (val << 6) + (int)(found - b64_table);
    valb += 6;
    if (valb >= 0) {
      out[pos++] = (unsigned char)((val >> valb) & 0xFF);
      valb -= 8;
    }
  }
  *out_len = pos;
  return out;
}

static int parse_bridge(const char *base, char *host, size_t host_len, int *port) {
  const char *prefix = "http://";
  if (strncmp(base, prefix, strlen(prefix)) != 0) return -1;
  const char *start = base + strlen(prefix);
  const char *colon = strrchr(start, ':');
  if (!colon) return -1;
  size_t n = (size_t)(colon - start);
  if (n == 0 || n >= host_len) return -1;
  memcpy(host, start, n);
  host[n] = '\0';
  *port = atoi(colon + 1);
  return *port > 0 ? 0 : -1;
}

static int connect_tcp(const char *host, int port) {
  char port_str[16];
  snprintf(port_str, sizeof(port_str), "%d", port);
  struct addrinfo hints;
  memset(&hints, 0, sizeof(hints));
  hints.ai_socktype = SOCK_STREAM;
  struct addrinfo *result = NULL;
  if (getaddrinfo(host, port_str, &hints, &result) != 0) return -1;
  int fd = -1;
  for (struct addrinfo *ai = result; ai; ai = ai->ai_next) {
    fd = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
    if (fd < 0) continue;
    if (connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
    close(fd);
    fd = -1;
  }
  freeaddrinfo(result);
  return fd;
}

static char *read_all(int fd, size_t *out_len) {
  size_t cap = 8192;
  size_t len = 0;
  char *buf = malloc(cap + 1);
  if (!buf) return NULL;
  while (true) {
    if (len + BUFFER_SIZE + 1 > cap) {
      cap *= 2;
      char *next = realloc(buf, cap + 1);
      if (!next) { free(buf); return NULL; }
      buf = next;
    }
    ssize_t n = read(fd, buf + len, BUFFER_SIZE);
    if (n < 0) {
      if (errno == EINTR) continue;
      free(buf); return NULL;
    }
    if (n == 0) break;
    len += (size_t)n;
  }
  buf[len] = '\0';
  *out_len = len;
  return buf;
}

static char *json_get_string(const char *json, const char *key) {
  char pattern[128];
  snprintf(pattern, sizeof(pattern), "\"%s\"", key);
  char *p = strstr((char *)json, pattern);
  if (!p) return NULL;
  p = strchr(p + strlen(pattern), ':');
  if (!p) return NULL;
  p++;
  while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
  if (*p != '"') return NULL;
  p++;
  char *out = malloc(strlen(p) + 1);
  if (!out) return NULL;
  size_t j = 0;
  for (; *p; p++) {
    if (*p == '"') break;
    if (*p == '\\') {
      p++;
      if (!*p) break;
      switch (*p) {
        case 'n': out[j++] = '\n'; break;
        case 'r': out[j++] = '\r'; break;
        case 't': out[j++] = '\t'; break;
        default: out[j++] = *p;
      }
    } else {
      out[j++] = *p;
    }
  }
  out[j] = '\0';
  return out;
}

static long json_get_long(const char *json, const char *key, long fallback) {
  char pattern[128];
  snprintf(pattern, sizeof(pattern), "\"%s\"", key);
  char *p = strstr((char *)json, pattern);
  if (!p) return fallback;
  p = strchr(p + strlen(pattern), ':');
  if (!p) return fallback;
  return strtol(p + 1, NULL, 10);
}

int main(int argc, char **argv) {
  bool silent = false;
  bool show_headers = false;
  const char *method = NULL;
  const char *data = NULL;
  const char *output_path = NULL;
  const char *url = NULL;
  struct header *headers = NULL;

  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "-s") == 0 || strcmp(argv[i], "-S") == 0 || strcmp(argv[i], "-sS") == 0 || strcmp(argv[i], "-L") == 0) {
      silent = true;
    } else if (strcmp(argv[i], "-I") == 0 || strcmp(argv[i], "--head") == 0) {
      show_headers = true;
      method = "HEAD";
    } else if ((strcmp(argv[i], "-X") == 0 || strcmp(argv[i], "--request") == 0) && i + 1 < argc) {
      method = argv[++i];
    } else if ((strcmp(argv[i], "-H") == 0 || strcmp(argv[i], "--header") == 0) && i + 1 < argc) {
      struct header *h = calloc(1, sizeof(*h));
      h->value = dupstr(argv[++i]);
      h->next = headers;
      headers = h;
    } else if ((strcmp(argv[i], "-d") == 0 || strcmp(argv[i], "--data") == 0 || strcmp(argv[i], "--data-raw") == 0) && i + 1 < argc) {
      data = argv[++i];
      if (!method) method = "POST";
    } else if ((strcmp(argv[i], "-o") == 0 || strcmp(argv[i], "--output") == 0) && i + 1 < argc) {
      output_path = argv[++i];
    } else if (argv[i][0] == '-') {
      if (!silent) fprintf(stderr, "curl: unsupported option: %s\n", argv[i]);
      return 2;
    } else {
      url = argv[i];
    }
  }
  if (!url) { usage(); return 2; }
  if (!method) method = "GET";
  const char *bridge = getenv("LETTA_ANDROID_NETWORK_BRIDGE_URL");
  if (!bridge || !*bridge) {
    fprintf(stderr, "curl: LETTA_ANDROID_NETWORK_BRIDGE_URL is not set\n");
    return 7;
  }
  char host[256];
  int port = 0;
  if (parse_bridge(bridge, host, sizeof(host), &port) != 0) {
    fprintf(stderr, "curl: unsupported bridge URL: %s\n", bridge);
    return 7;
  }
  char tmp_template[] = "curlreqXXXXXX";
  int tmpfd = mkstemp(tmp_template);
  if (tmpfd < 0) return 2;
  FILE *json = fdopen(tmpfd, "w");
  if (!json) return 2;
  fputs("{\"url\":", json); append_json_string(json, url);
  fputs(",\"method\":", json); append_json_string(json, method);
  fputs(",\"headers\":{", json);
  bool first = true;
  for (struct header *h = headers; h; h = h->next) {
    char *colon = strchr(h->value, ':');
    if (!colon) continue;
    *colon = '\0';
    if (!first) fputc(',', json);
    first = false;
    append_json_string(json, h->value);
    fputc(':', json);
    append_json_string(json, colon + 1);
  }
  fputc('}', json);
  if (data) { fputs(",\"body\":", json); append_json_string(json, data); }
  fputc('}', json);
  fclose(json);

  FILE *reqfile = fopen(tmp_template, "rb");
  if (!reqfile) return 2;
  fseek(reqfile, 0, SEEK_END);
  long body_len = ftell(reqfile);
  fseek(reqfile, 0, SEEK_SET);
  if (body_len < 0 || body_len > MAX_BODY) return 2;
  char *body = malloc((size_t)body_len + 1);
  fread(body, 1, (size_t)body_len, reqfile);
  fclose(reqfile);
  unlink(tmp_template);
  body[body_len] = '\0';

  int fd = connect_tcp(host, port);
  if (fd < 0) { fprintf(stderr, "curl: could not connect to Android network bridge\n"); return 7; }
  dprintf(fd,
          "POST /fetch HTTP/1.1\r\nHost: %s:%d\r\nContent-Type: application/json\r\nContent-Length: %ld\r\nConnection: close\r\n\r\n",
          host, port, body_len);
  write(fd, body, (size_t)body_len);
  free(body);
  size_t response_len = 0;
  char *response = read_all(fd, &response_len);
  close(fd);
  if (!response) return 7;
  char *json_start = strstr(response, "\r\n\r\n");
  if (!json_start) { free(response); return 7; }
  json_start += 4;
  long status = json_get_long(json_start, "status", 0);
  char *status_text = json_get_string(json_start, "statusText");
  char *body64 = json_get_string(json_start, "bodyBase64");
  if (!body64) { free(response); free(status_text); return 7; }
  size_t decoded_len = 0;
  unsigned char *decoded = base64_decode(body64, &decoded_len);
  free(body64);
  FILE *out = stdout;
  if (output_path) out = fopen(output_path, "wb");
  if (!out) { free(response); free(status_text); free(decoded); return 23; }
  if (show_headers) {
    fprintf(out, "HTTP/1.1 %ld %s\r\n\r\n", status, status_text ? status_text : "");
  } else if (decoded && decoded_len > 0) {
    fwrite(decoded, 1, decoded_len, out);
  }
  if (output_path) fclose(out);
  free(decoded);
  free(status_text);
  free(response);
  return (status >= 200 && status < 400) ? 0 : 22;
}
