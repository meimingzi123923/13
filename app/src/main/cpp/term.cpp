// JNI 终端实现：forkpty 创建伪终端，供 TerminalService 读写。
// 对应 Kotlin：com.pocketcode.studio.core.terminal.PcsTerm（JNI 绑定层）
// 符号名必须与 PcsTerm.kt 的 object 名一致，否则 UnsatisfiedLinkError。
#include <jni.h>
#include <android/log.h>

#include <cstdlib>
#include <cstring>
#include <string>

#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "pcs_term"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static pid_t g_child_pid = -1;

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketcode_studio_core_terminal_PcsTerm_nativeCreateSubprocess(
        JNIEnv *env, jclass /*clazz*/, jstring cmd, jstring cwd) {
    const char *c_cmd = env->GetStringUTFChars(cmd, nullptr);
    const char *c_cwd = env->GetStringUTFChars(cwd, nullptr);

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, nullptr, nullptr, nullptr);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        env->ReleaseStringUTFChars(cmd, c_cmd);
        env->ReleaseStringUTFChars(cwd, c_cwd);
        return -1;
    }

    if (pid == 0) {
        // 子进程：切换工作目录后执行 shell -c "<cmd>"
        if (c_cwd != nullptr && c_cwd[0] != '\0') {
            if (chdir(c_cwd) != 0) {
                LOGI("chdir(%s) failed", c_cwd);
            }
        }
        setenv("TERM", "xterm-256color", 1);
        setenv("LANG", "en_US.UTF-8", 1);

        const char *shell = "/system/bin/sh";
        if (access(shell, X_OK) != 0) shell = "/bin/sh";

        // 把 "su" 这类命令交给 shell 执行
        execl(shell, "sh", "-c", c_cmd, (char *) nullptr);
        _exit(127);
    }

    // 父进程
    g_child_pid = pid;
    // 设置非阻塞，读线程遇到 EAGAIN 时轮询
    int flags = fcntl(master_fd, F_GETFL, 0);
    fcntl(master_fd, F_SETFL, flags & ~O_NONBLOCK); // 阻塞读更省电

    env->ReleaseStringUTFChars(cmd, c_cmd);
    env->ReleaseStringUTFChars(cwd, c_cwd);
    return master_fd;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketcode_studio_core_terminal_PcsTerm_nativeRead(
        JNIEnv *env, jclass /*clazz*/, jint fd, jbyteArray buf) {
    jsize len = env->GetArrayLength(buf);
    jbyte *data = env->GetByteArrayElements(buf, nullptr);
    if (data == nullptr) return -1;

    ssize_t n;
    do {
        n = read(fd, data, (size_t) len);
    } while (n < 0 && errno == EINTR);

    env->ReleaseByteArrayElements(buf, data, 0);
    if (n < 0) return -1;
    return (jint) n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketcode_studio_core_terminal_PcsTerm_nativeWrite(
        JNIEnv *env, jclass /*clazz*/, jint fd, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return;

    ssize_t off = 0;
    while (off < len) {
        ssize_t n = write(fd, bytes + off, (size_t) (len - off));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        off += n;
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketcode_studio_core_terminal_PcsTerm_nativeResize(
        JNIEnv *env, jclass /*clazz*/, jint fd, jint rows, jint cols) {
    (void) env;
    struct winsize ws{};
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    ioctl(fd, TIOCSWINSZ, &ws);
    if (g_child_pid > 0) kill(g_child_pid, SIGWINCH);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketcode_studio_core_terminal_PcsTerm_nativeKill(
        JNIEnv * /*env*/, jclass /*clazz*/, jint fd) {
    if (g_child_pid > 0) {
        kill(g_child_pid, SIGHUP);
        kill(g_child_pid, SIGKILL);
        waitpid(g_child_pid, nullptr, WNOHANG);
        g_child_pid = -1;
    }
    if (fd >= 0) close(fd);
}