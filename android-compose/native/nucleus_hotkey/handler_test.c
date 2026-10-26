/* Native CI regression: compile the exact upstream/patched implementation. */
#include "nucleus_global_hotkey_linux.c"

static int sentinel_errors;
static int sentinel(Display *display, XErrorEvent *error) {
    (void)display;
    if (error->error_code != BadWindow) abort();
    sentinel_errors++;
    return 0;
}

static void preserves_handler(const char *phase) {
    XErrorHandler observed = XSetErrorHandler(sentinel);
    if (observed != sentinel) {
        fprintf(stderr, "LOST_HANDLER: %s\n", phase);
        exit(42);
    }
    /* This error must reach the restored host handler, not be suppressed. */
    int before = sentinel_errors;
    XDestroyWindow(g_display, None);
    XSync(g_display, False);
    if (sentinel_errors != before + 1) abort();
    printf("PASS: %s preserved handler and forwarded BadWindow\n", phase);
}

int main(void) {
    if (!XInitThreads()) return 1;
    Display *owner = XOpenDisplay(NULL);
    Display *contender = XOpenDisplay(NULL);
    if (!owner || !contender) return 1;
    XErrorHandler prior = XSetErrorHandler(sentinel);
    HotKeyEntry entry = { .x11_keycode = XKeysymToKeycode(owner, XK_F12),
                          .x11_modifiers = ControlMask | ShiftMask };
    if (!entry.x11_keycode) return 1;
    g_rootWindow = DefaultRootWindow(owner);
    for (int i = 0; i < 3; i++) {
        g_display = owner;
        if (x11_grab(&entry) != 0) return 1;
        preserves_handler("successful grab");
        g_display = contender;
        if (x11_grab(&entry) != -1) return 1;
        preserves_handler("conflicting grab");
        g_display = owner;
        x11_ungrab(&entry);
        preserves_handler("unregister");
        g_display = contender;
        if (x11_grab(&entry) != 0) return 1;
        preserves_handler("register after conflict cleared");
        x11_ungrab(&entry);
        preserves_handler("final unregister");
    }
    XCloseDisplay(contender);
    XCloseDisplay(owner);
    XSetErrorHandler(prior);
    puts("PASS: three success/conflict/unregister/retry cycles");
    return 0;
}
