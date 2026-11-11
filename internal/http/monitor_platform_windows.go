//go:build windows

package httpapi

import (
	"syscall"
	"time"
	"unsafe"
)

var (
	kernel32                       = syscall.MustLoadDLL("kernel32.dll")
	procGetCurrentProcess          = kernel32.MustFindProc("GetCurrentProcess")
	procGetProcessTimes            = kernel32.MustFindProc("GetProcessTimes")
	procGetConsoleScreenBufferInfo = kernel32.MustFindProc("GetConsoleScreenBufferInfo")
)

// readCPUTime 通过 GetProcessTimes 读取进程累计 CPU 时间（内核态 + 用户态）。
func readCPUTime() time.Duration {
	handle, _, _ := procGetCurrentProcess.Call()
	if handle == 0 {
		return 0
	}
	var creation, exit, kernel, user syscall.Filetime
	ok, _, _ := procGetProcessTimes.Call(
		handle,
		uintptr(unsafe.Pointer(&creation)),
		uintptr(unsafe.Pointer(&exit)),
		uintptr(unsafe.Pointer(&kernel)),
		uintptr(unsafe.Pointer(&user)),
	)
	if ok == 0 {
		return 0
	}
	return time.Duration(kernel.Nanoseconds() + user.Nanoseconds())
}

type winCoord struct {
	X int16
	Y int16
}

type winSmallRect struct {
	Left   int16
	Top    int16
	Right  int16
	Bottom int16
}

type winConsoleScreenBufferInfo struct {
	Size              winCoord
	CursorPosition    winCoord
	Attributes        uint16
	Window            winSmallRect
	MaximumWindowSize winCoord
}

// platformTerminalSize 通过 GetConsoleScreenBufferInfo 读取控制台窗口尺寸。
func platformTerminalSize() (int, int) {
	var info winConsoleScreenBufferInfo
	ok, _, _ := procGetConsoleScreenBufferInfo.Call(
		uintptr(syscall.Stdout),
		uintptr(unsafe.Pointer(&info)),
	)
	if ok == 0 {
		return 80, 24
	}
	width := int(info.Window.Right-info.Window.Left) + 1
	height := int(info.Window.Bottom-info.Window.Top) + 1
	if width <= 0 || height <= 0 {
		return 80, 24
	}
	return width, height
}
