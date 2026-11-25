#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

static void fail(const char *operation) {
    perror(operation);
    exit(EXIT_FAILURE);
}

static void path(char *out, const char *root, const char *entry) {
    int length = snprintf(out, PATH_MAX, "%s%s", root, entry);
    if (length < 0 || length >= PATH_MAX) {
        errno = ENAMETOOLONG;
        fail("path");
    }
}

static void bind_at(const char *source, const char *target, unsigned long flags) {
    if (mount(source, target, NULL, MS_BIND, NULL) != 0 ||
        mount(NULL, target, NULL, MS_BIND | MS_REMOUNT | MS_NOSUID | MS_NOEXEC | flags, NULL) != 0)
        fail("private bind mount");
}

static volatile sig_atomic_t child_pid;

static void forward_signal(int signal_number) {
    if (child_pid > 0) kill((pid_t) child_pid, signal_number);
}

int main(int argc, char **argv) {
    if (argc != 5 || (strcmp(argv[4], "keygen") && strcmp(argv[4], "serve"))) {
        fprintf(stderr, "usage: launcher ROOT STATE APP_UID {keygen|serve}\n");
        return EXIT_FAILURE;
    }
    char *end;
    long uid = strtol(argv[3], &end, 10);
    if (*end != '\0' || uid < 10000 || uid > INT_MAX) {
        fprintf(stderr, "invalid Android application UID\n");
        return EXIT_FAILURE;
    }
    if (unshare(CLONE_NEWNS | CLONE_NEWPID) != 0 || mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0)
        fail("private ARTEMIS namespaces");
    pid_t pid = fork();
    if (pid < 0) fail("fork");
    if (pid > 0) {
        child_pid = pid;
        struct sigaction action = {0};
        action.sa_handler = forward_signal;
        sigemptyset(&action.sa_mask);
        if (sigaction(SIGTERM, &action, NULL) != 0) fail("sigaction");
        int status;
        while (waitpid(pid, &status, 0) < 0) {
            if (errno != EINTR) fail("waitpid");
        }
        return WIFEXITED(status) ? WEXITSTATUS(status) : EXIT_FAILURE;
    }

    char target[PATH_MAX];
    path(target, argv[1], "/proc");
    if (mount("proc", target, "proc", MS_RDONLY | MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL) != 0)
        fail("private proc mount");
    path(target, argv[1], "/runtime");
    bind_at(argv[2], target, MS_NODEV);
    path(target, argv[1], "/dev");
    if (mount("tmpfs", target, "tmpfs", MS_NOSUID | MS_NOEXEC, "mode=755,size=16m") != 0)
        fail("private dev mount");
    const char *devices[] = {"/dev/null", "/dev/zero", "/dev/urandom", "/dev/random", "/dev/tty"};
    for (size_t i = 0; i < sizeof(devices) / sizeof(devices[0]); i++) {
        path(target, argv[1], devices[i]);
        int fd = open(target, O_CREAT | O_NOFOLLOW | O_WRONLY | O_CLOEXEC, 0600);
        if (fd < 0) fail("device mount point");
        close(fd);
        bind_at(devices[i], target, 0);
    }
    path(target, argv[1], "/dev/shm");
    if (mkdir(target, 01777) != 0 || chmod(target, 01777) != 0)
        fail("private shm directory");
    if (chroot(argv[1]) != 0 || chdir("/opt/artemis") != 0)
        fail("chroot");
    if (setgroups(0, NULL) != 0 || setgid((gid_t) uid) != 0 || setuid((uid_t) uid) != 0 ||
        prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0)
        fail("drop privileges");

    umask(0077);
    char *const args[] = {"/opt/bridge/run", argv[4], NULL};
    char *const env[] = {
        "PATH=/opt/bridge/bin:/usr/bin:/bin",
        "HOME=/runtime/home",
        "TMPDIR=/runtime/tmp",
        "ARTEMIS_APP_DIR=/runtime/artemis",
        "LANG=C.UTF-8",
        NULL
    };
    execve(args[0], args, env);
    fail("ARTEMIS entry point");
    return EXIT_FAILURE;
}
