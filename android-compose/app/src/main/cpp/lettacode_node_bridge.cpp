#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <node.h>
#include <signal.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <exception>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char* kTag = "LettaCodeNodeBridge";

JavaVM* gJvm = nullptr;
jclass gBridgeClass = nullptr;
jmethodID gStdoutMethod = nullptr;
jmethodID gStderrMethod = nullptr;
int gStdinWriteFd = -1;
std::atomic<bool> gStopRequested(false);

void LogError(const char* message) {
    __android_log_write(ANDROID_LOG_ERROR, kTag, message);
}

void LogInfo(const char* message) {
    __android_log_write(ANDROID_LOG_INFO, kTag, message);
}

void LogInfo(const std::string& message) {
    LogInfo(message.c_str());
}

void LogError(const std::string& message) {
    LogError(message.c_str());
}

void LogPhase(const char* phase) {
    std::string message = "embedded node phase: ";
    message += phase;
    LogInfo(message);
    dprintf(STDERR_FILENO, "[lettacode-node-bridge] %s\n", message.c_str());
}

void AbortSignalHandler(int signalNumber) {
    const char message[] = "[lettacode-node-bridge] embedded node received SIGABRT before returning from node::Start\n";
    __android_log_write(ANDROID_LOG_FATAL, kTag, message);
    write(STDERR_FILENO, message, sizeof(message) - 1);
    signal(signalNumber, SIG_DFL);
    raise(signalNumber);
}

void InstallFatalSignalDiagnostics() {
    struct sigaction action = {};
    action.sa_handler = AbortSignalHandler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_RESETHAND;
    sigaction(SIGABRT, &action, nullptr);
}

void SetEnvDefault(const char* key, const char* value) {
    if (getenv(key) == nullptr) {
        setenv(key, value, 0);
    }
}

std::string ReadSmallFile(const char* path) {
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        return {};
    }
    char buffer[1024];
    const ssize_t readCount = read(fd, buffer, sizeof(buffer) - 1);
    close(fd);
    if (readCount <= 0) {
        return {};
    }
    buffer[readCount] = '\0';
    return std::string(buffer, static_cast<size_t>(readCount));
}

void LogPathProbe(const char* path) {
    struct stat info = {};
    errno = 0;
    if (stat(path, &info) == 0) {
        LogInfo(std::string("embedded node path probe ok: ") + path);
        return;
    }
    LogInfo(
        std::string("embedded node path probe failed: ") + path +
        " errno=" + std::to_string(errno) + " (" + strerror(errno) + ")"
    );
}

void ApplyAndroidLibuvMitigations() {
    SetEnvDefault("UV_USE_IO_URING", "0");
    SetEnvDefault("UV_THREADPOOL_SIZE", "2");
    SetEnvDefault("NODE_OPTIONS", "--max-old-space-size=384 --max-semi-space-size=16");
}

void DisableStdStreamBuffering() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
}

void LogAndroidResourceProbe() {
    const std::string cgroup = ReadSmallFile("/proc/self/cgroup");
    if (cgroup.empty()) {
        LogInfo("embedded node /proc/self/cgroup probe empty or denied");
    } else {
        LogInfo(std::string("embedded node /proc/self/cgroup: ") + cgroup.substr(0, 240));
    }
    LogPathProbe("/sys/fs/cgroup");
    LogPathProbe("/sys/fs/cgroup/memory/memory.limit_in_bytes");
    LogPathProbe("/sys/fs/cgroup/memory.max");
}

std::string ToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

std::vector<std::string> ToStringVector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    if (array == nullptr) return result;
    const jsize count = env->GetArrayLength(array);
    result.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(ToString(env, item));
        env->DeleteLocalRef(item);
    }
    return result;
}

void EmitLine(jmethodID method, const std::string& line) {
    if (line.empty() || gJvm == nullptr || gBridgeClass == nullptr || method == nullptr) {
        return;
    }
    JNIEnv* env = nullptr;
    bool detach = false;
    jint status = gJvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        detach = true;
    } else if (status != JNI_OK) {
        return;
    }

    jstring jLine = env->NewStringUTF(line.c_str());
    env->CallStaticVoidMethod(gBridgeClass, method, jLine);
    env->DeleteLocalRef(jLine);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (detach) {
        gJvm->DetachCurrentThread();
    }
}

void PumpFdLines(int fd, jmethodID method) {
    std::string buffer;
    char chunk[1024];
    while (!gStopRequested.load()) {
        const ssize_t readCount = read(fd, chunk, sizeof(chunk));
        if (readCount <= 0) {
            if (readCount < 0 && errno == EINTR) continue;
            break;
        }
        buffer.append(chunk, static_cast<size_t>(readCount));
        size_t pos = 0;
        while ((pos = buffer.find('\n')) != std::string::npos) {
            std::string line = buffer.substr(0, pos);
            if (!line.empty() && line.back() == '\r') {
                line.pop_back();
            }
            EmitLine(method, line);
            buffer.erase(0, pos + 1);
        }
    }
    if (!buffer.empty()) {
        EmitLine(method, buffer);
    }
    close(fd);
}

bool RedirectFd(int targetFd, int pipeFds[2], bool keepWriteEnd) {
    if (pipe(pipeFds) != 0) {
        LogError("pipe() failed");
        return false;
    }
    const int sourceFd = keepWriteEnd ? pipeFds[0] : pipeFds[1];
    if (dup2(sourceFd, targetFd) < 0) {
        LogError("dup2() failed");
        close(pipeFds[0]);
        close(pipeFds[1]);
        return false;
    }
    close(sourceFd);
    return true;
}

void CloseIfOpen(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/letta/mobile/runtime/local/NativeLettaCodeNodeBridge");
    if (localClass == nullptr) {
        return JNI_ERR;
    }
    gBridgeClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    gStdoutMethod = env->GetStaticMethodID(gBridgeClass, "onNativeStdoutLine", "(Ljava/lang/String;)V");
    gStderrMethod = env->GetStaticMethodID(gBridgeClass, "onNativeStderrLine", "(Ljava/lang/String;)V");
    if (gStdoutMethod == nullptr || gStderrMethod == nullptr) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_letta_mobile_runtime_local_NativeLettaCodeNodeBridge_nativeStart(
    JNIEnv* env,
    jobject,
    jobjectArray arguments,
    jobjectArray environment,
    jstring workingDirectory
) {
    InstallFatalSignalDiagnostics();
    LogPhase("nativeStart entered");
    ApplyAndroidLibuvMitigations();
    LogAndroidResourceProbe();

    const std::string cwd = ToString(env, workingDirectory);
    if (!cwd.empty() && chdir(cwd.c_str()) != 0) {
        LogError("chdir() failed");
        return -1;
    }
    LogPhase("working directory configured");

    for (const auto& item : ToStringVector(env, environment)) {
        const size_t equals = item.find('=');
        if (equals == std::string::npos) continue;
        const std::string key = item.substr(0, equals);
        const std::string value = item.substr(equals + 1);
        setenv(key.c_str(), value.c_str(), 1);
    }
    ApplyAndroidLibuvMitigations();
    LogPhase("environment configured");

    int stdinPipe[2] = {-1, -1};
    int stdoutPipe[2] = {-1, -1};
    int stderrPipe[2] = {-1, -1};
    auto cleanupRedirects = [&]() {
        CloseIfOpen(gStdinWriteFd);
        CloseIfOpen(stdoutPipe[0]);
        CloseIfOpen(stderrPipe[0]);
    };

    if (!RedirectFd(STDIN_FILENO, stdinPipe, true)) {
        cleanupRedirects();
        return -1;
    }
    gStdinWriteFd = stdinPipe[1];
    if (!RedirectFd(STDOUT_FILENO, stdoutPipe, false)) {
        cleanupRedirects();
        return -1;
    }
    if (!RedirectFd(STDERR_FILENO, stderrPipe, false)) {
        cleanupRedirects();
        return -1;
    }
    DisableStdStreamBuffering();

    gStopRequested.store(false);
    std::thread(PumpFdLines, stdoutPipe[0], gStdoutMethod).detach();
    std::thread(PumpFdLines, stderrPipe[0], gStderrMethod).detach();
    LogPhase("stdio redirected");

    std::vector<std::string> args = ToStringVector(env, arguments);
    std::vector<char*> argv;
    argv.reserve(args.size());
    for (auto& arg : args) {
        argv.push_back(arg.data());
    }
    LogInfo(std::string("embedded node argv count: ") + std::to_string(argv.size()));
    LogPhase("before node::Start");
    int exitCode = -1;
    try {
        exitCode = node::Start(static_cast<int>(argv.size()), argv.data());
    } catch (const std::exception& error) {
        LogError(std::string("node::Start threw std::exception: ") + error.what());
        dprintf(STDERR_FILENO, "[lettacode-node-bridge] node::Start threw: %s\n", error.what());
        return -1;
    } catch (...) {
        LogError("node::Start threw non-standard exception");
        dprintf(STDERR_FILENO, "[lettacode-node-bridge] node::Start threw non-standard exception\n");
        return -1;
    }
    LogInfo(std::string("embedded node::Start returned: ") + std::to_string(exitCode));
    return exitCode;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_letta_mobile_runtime_local_NativeLettaCodeNodeBridge_nativeWriteStdin(
    JNIEnv* env,
    jobject,
    jstring line
) {
    if (gStdinWriteFd < 0) {
        return JNI_FALSE;
    }
    const std::string payload = ToString(env, line);
    const char* data = payload.data();
    size_t remaining = payload.size();
    while (remaining > 0) {
        const ssize_t written = write(gStdinWriteFd, data, remaining);
        if (written < 0) {
            if (errno == EINTR) continue;
            return JNI_FALSE;
        }
        if (written == 0) {
            return JNI_FALSE;
        }
        data += written;
        remaining -= static_cast<size_t>(written);
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_letta_mobile_runtime_local_NativeLettaCodeNodeBridge_nativeStop(
    JNIEnv*,
    jobject
) {
    gStopRequested.store(true);
    if (gStdinWriteFd >= 0) {
        close(gStdinWriteFd);
        gStdinWriteFd = -1;
    }
    return JNI_TRUE;
}
