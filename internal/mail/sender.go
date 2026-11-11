package mail

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

func SendCode(toEmail, code string) error {
	return SendMessage(toEmail, "旧聊验证码", "你的验证码是: "+code+"\n有效期10分钟。如非本人操作请忽略。")
}

// SendMessage 发送任意主题和内容的邮件（纯文本）。
func SendMessage(toEmail, subject, content string) error {
	script, err := mailScriptPath()
	if err != nil {
		return err
	}
	cmd := exec.Command("python3", script, toEmail, subject, content)
	cmd.Dir = filepath.Dir(script)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("mail failed: %v (%s)", err, string(out))
	}
	return nil
}

func mailScriptPath() (string, error) {
	if custom := os.Getenv("MAIL_SCRIPT_PATH"); custom != "" {
		if _, err := os.Stat(custom); err == nil {
			return custom, nil
		}
	}
	var bases []string
	if exe, err := os.Executable(); err == nil {
		bases = append(bases, filepath.Dir(exe))
	}
	if wd, err := os.Getwd(); err == nil {
		bases = append(bases, wd)
	}

	for _, base := range bases {
		curr := base
		for i := 0; i < 5; i++ {
			candidates := []string{
				filepath.Join(curr, "mail_project", "send_message.py"),
				filepath.Join(curr, "server", "mail_project", "send_message.py"),
				filepath.Join(curr, "mail_project", "send_code.py"),
				filepath.Join(curr, "server", "mail_project", "send_code.py"),
			}
			for _, c := range candidates {
				if _, err := os.Stat(c); err == nil {
					return c, nil
				}
			}
			parent := filepath.Dir(curr)
			if parent == curr {
				break
			}
			curr = parent
		}
	}
	return "", fmt.Errorf("send_message.py not found")
}
