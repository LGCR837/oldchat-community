//go:build unix

package httpapi

import (
	"os"
	"syscall"
	"time"
	"unsafe"
)

// readCPUTime 返回当前进程累计消耗的 CPU 时间（用户态 + 内核态）。
func readCPUTime() time.Duration {
	var ru syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &ru); err != nil {
		return 0
	}
	sec := time.Duration(ru.Utime.Sec+ru.Stime.Sec) * time.Second
	usec := time.Duration(ru.Utime.Usec+ru.Stime.Usec) * time.Microsecond
	return sec + usec
}

// platformTerminalSize 通过 TIOCGWINSZ 读取终端窗口尺寸。
func platformTerminalSize() (int, int) {
	var ws struct {
		Row    uint16
		Col    uint16
		Xpixel uint16
		Ypixel uint16
	}
	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, os.Stdout.Fd(), uintptr(syscall.TIOCGWINSZ), uintptr(unsafe.Pointer(&ws)))
	if errno == 0 && ws.Col > 0 && ws.Row > 0 {
		return int(ws.Col), int(ws.Row)
	}
	return 80, 24
}
