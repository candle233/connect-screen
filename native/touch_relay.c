#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <glob.h>
#include <linux/input.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <unistd.h>

static volatile sig_atomic_t stopping;
static void stop(int sig) { (void)sig; stopping = 1; }

static int checked_open(const char *path) {
    /* Exact name + USB bus + direct MT axes: a renamed internal panel cannot pass. */
    if (!strcmp(path, "/dev/input/event2")) { errno = EPERM; return -1; }
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    char name[256] = {0};
    struct input_id id = {0};
    struct input_absinfo ax, ay, slots;
    unsigned long props[4] = {0};
    if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) < 0 || strcmp(name, "ILITEK ILITEK-TP")
            || ioctl(fd, EVIOCGID, &id) < 0 || id.bustype != BUS_USB
            || ioctl(fd, EVIOCGPROP(sizeof(props)), props) < 0
            || !(props[0] & (1UL << INPUT_PROP_DIRECT))
            || ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ax) < 0
            || ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ay) < 0
            || ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slots) < 0
            || ax.minimum != 0 || ay.minimum != 0 || ax.maximum != 16384 || ay.maximum != 16384) {
        close(fd); errno = ENODEV; return -1;
    }
    return fd;
}

static int find_device(char *path, size_t capacity) {
    glob_t devices = {0};
    if (glob("/dev/input/event*", 0, NULL, &devices) != 0) return -1;
    int found = 0;
    for (size_t i = 0; i < devices.gl_pathc; ++i) {
        int candidate = checked_open(devices.gl_pathv[i]);
        if (candidate >= 0) {
            close(candidate);
            snprintf(path, capacity, "%s", devices.gl_pathv[i]);
            ++found;
        }
    }
    globfree(&devices);
    if (found != 1) {
        fprintf(stderr, "Expected one verified ILITEK USB direct touchscreen; found %d\n", found);
        return -1;
    }
    return 0;
}

int main(int argc, char **argv) {
    char device[256];
    if (argc > 2 || (argc == 2 && strcmp(argv[1], "auto")
            && (strncmp(argv[1], "/dev/input/event", 16)
                || !argv[1][16] || strspn(argv[1] + 16, "0123456789") != strlen(argv[1] + 16)
                || !strcmp(argv[1], "/dev/input/event2")))) {
        fprintf(stderr, "Only a verified external ILITEK event device is permitted\n");
        return 2;
    }
    setvbuf(stdout, NULL, _IOLBF, 0);
    struct sigaction sa = {0};
    sa.sa_handler = stop;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    sigaction(SIGPIPE, &sa, NULL);
    pid_t parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) < 0 || getppid() != parent) return 3;
    if (argc == 1 || !strcmp(argv[1], "auto")) {
        if (find_device(device, sizeof(device)) < 0) return 4;
    } else snprintf(device, sizeof(device), "%s", argv[1]);
    int fd = checked_open(device); // Recheck the opened fd before grabbing, avoiding path races.
    if (fd < 0) { perror("verify external ILITEK device"); return 5; }
    if (ioctl(fd, EVIOCGRAB, 1) < 0) { perror("EVIOCGRAB"); close(fd); return 6; }
    fprintf(stderr, "READY grabbed %s (ILITEK ILITEK-TP; USB; ABS_MT_POSITION_X/Y; INPUT_PROP_DIRECT)\n", device);

    /* Recover initial slot and contact state, then commit only at SYN_REPORT. */
    struct input_absinfo abs;
    int slot = 0, tracking = -1, sentTracking = -1, x = 0, y = 0;
    int haveX = 0, haveY = 0, changed = 0, result = 0, lastX = 0, lastY = 0;
    if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &abs) == 0) slot = abs.value;
    int slots[11] = {ABS_MT_TRACKING_ID};
    if (ioctl(fd, EVIOCGMTSLOTS(sizeof(slots)), slots) == 0) tracking = slots[1];
    slots[0] = ABS_MT_POSITION_X;
    if (ioctl(fd, EVIOCGMTSLOTS(sizeof(slots)), slots) == 0) { x = slots[1]; haveX = 1; }
    slots[0] = ABS_MT_POSITION_Y;
    if (ioctl(fd, EVIOCGMTSLOTS(sizeof(slots)), slots) == 0) { y = slots[1]; haveY = 1; }
    struct pollfd pfd = {fd, POLLIN, 0};
    while (!stopping) {
        int ready = poll(&pfd, 1, 250);
        if (ready < 0) { if (errno == EINTR) continue; perror("poll"); result = 7; break; }
        if (!ready) continue;
        if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            fprintf(stderr, "%s disconnected or poll failed: revents=0x%x\n", device, pfd.revents);
            result = 8; break;
        }
        struct input_event events[64];
        ssize_t bytes = read(fd, events, sizeof(events));
        if (bytes < 0) { if (errno == EINTR || errno == EAGAIN) continue; perror("read"); result = 9; break; }
        if (!bytes || bytes % sizeof(events[0])) { result = 10; break; }
        for (size_t i = 0; i < (size_t)bytes / sizeof(events[0]); ++i) {
            struct input_event *ev = &events[i];
            if (ev->type == EV_SYN && ev->code == SYN_DROPPED) {
                fprintf(stderr, "SYN_DROPPED: stopping to release grab\n");
                stopping = 1; result = 11; break;
            }
            if (ev->type == EV_ABS) {
                if (ev->code == ABS_MT_SLOT) slot = ev->value;
                else if (slot == 0) {
                    if (ev->code == ABS_MT_TRACKING_ID) tracking = ev->value;
                    else if (ev->code == ABS_MT_POSITION_X) { x = ev->value; haveX = changed = 1; }
                    else if (ev->code == ABS_MT_POSITION_Y) { y = ev->value; haveY = changed = 1; }
                }
            } else if (ev->type == EV_SYN && ev->code == SYN_REPORT) {
                if (sentTracking >= 0 && tracking != sentTracking) {
                    printf("U %d %d\n", lastX, lastY); sentTracking = -1;
                }
                if (tracking >= 0 && haveX && haveY) {
                    if (sentTracking < 0) { printf("D %d %d\n", x, y); sentTracking = tracking; }
                    else if (changed) printf("M %d %d\n", x, y);
                    lastX = x; lastY = y;
                }
                changed = 0;
                if (ferror(stdout)) { stopping = 1; result = 12; break; }
            }
        }
    }
    if (ioctl(fd, EVIOCGRAB, 0) < 0) perror("release EVIOCGRAB");
    close(fd);
    fprintf(stderr, "RELEASED %s; exit=%d\n", device, result);
    return result;
}
