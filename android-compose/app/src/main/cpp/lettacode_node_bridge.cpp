#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <node.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <cstdlib>
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
    const std::string cwd = ToString(env, workingDirectory);
    if (!cwd.empty() && chdir(cwd.c_str()) != 0) {
        LogError("chdir() failed");
        return -1;
    }

    for (const auto& item : ToStringVector(env, environment)) {
        const size_t equals = item.find('=');
        if (equals == std::string::npos) continue;
        const std::string key = item.substr(0, equals);
        const std::string value = item.substr(equals + 1);
        setenv(key.c_str(), value.c_str(), 1);
    }

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

    gStopRequested.store(false);
    std::thread(PumpFdLines, stdoutPipe[0], gStdoutMethod).detach();
    std::thread(PumpFdLines, stderrPipe[0], gStderrMethod).detach();

    std::vector<std::string> args = ToStringVector(env, arguments);
    std::vector<char*> argv;
    argv.reserve(args.size());
    for (auto& arg : args) {
        argv.push_back(arg.data());
    }
    return node::Start(static_cast<int>(argv.size()), argv.data());
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
