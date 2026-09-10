/*
 * Copyright (c) 2024 Mobile Harness Contributors
 * Copyright (c) 2024 VibeIDE Contributors
 *
 * This file is part of VibeIDE.
 *
 * Ported from Mobile-Harness (https://github.com/techjarves/Mobile-Harness)
 * Original: app/src/main/cpp/pocket_launcher.c
 *
 * VibeIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VibeIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VibeIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 2) {
        fputs("Pocket launcher requires a program\n", stderr);
        return 64;
    }
    if (prctl(PR_SET_DUMPABLE, 1, 0, 0, 0) != 0) {
        perror("Pocket launcher could not enable child tracing");
        return 70;
    }
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("LD_PRELOAD");
    execv(argv[1], &argv[1]);
    perror("Pocket launcher exec failed");
    return errno == 0 ? 71 : errno;
}