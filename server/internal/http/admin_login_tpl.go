package httpapi

import "html/template"

type adminLoginData struct {
	Error   string
	Success string
}

const adminSeparateLoginHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>管理员登录 — Oldchat</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; border-radius: 0 !important; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans SC", monospace, sans-serif;
      font-size: 14px;
      line-height: 1.6;
      color: #050505;
      background: #f4f6f8;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      padding: 16px;
    }
    .login-card {
      background: #fff;
      border: 2px solid #050505;
      box-shadow: 4px 4px 0 #050505;
      width: 100%;
      max-width: 360px;
      padding: 28px 24px;
    }
    .login-header {
      margin-bottom: 20px;
      border-bottom: 2px solid #050505;
      padding-bottom: 12px;
    }
    h1 {
      font-size: 18px;
      font-weight: 900;
      color: #050505;
      letter-spacing: 0.5px;
    }
    .login-form {
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    input[type="password"] {
      width: 100%;
      padding: 10px 12px;
      border: 2px solid #050505;
      background: #fff;
      color: #050505;
      font-size: 14px;
      font-family: inherit;
      outline: none;
    }
    input[type="password"]:focus {
      box-shadow: 2px 2px 0 #050505;
    }
    button[type="submit"] {
      width: 100%;
      padding: 10px 14px;
      background: #050505;
      color: #fff;
      border: 2px solid #050505;
      font-size: 14px;
      font-weight: 800;
      cursor: pointer;
      transition: background 0.15s;
    }
    button[type="submit"]:hover {
      background: #222;
    }
    .err-msg {
      padding: 8px 10px;
      background: #fce8e6;
      border: 1.5px solid #c5221f;
      color: #c5221f;
      font-size: 13px;
      font-weight: 700;
      margin-bottom: 14px;
    }
  </style>
</head>
<body>
  <div class="login-card">
    <div class="login-header">
      <h1>Oldchat 管理员登录</h1>
    </div>

    {{if .Error}}
    <div class="err-msg">{{.Error}}</div>
    {{end}}

    <form class="login-form" method="post" action="/admins/login">
      <input type="password" name="secret" placeholder="输入管理密钥" autofocus required autocomplete="current-password">
      <button type="submit">登录</button>
    </form>
  </div>
</body>
</html>
`

var adminSeparateLoginTpl = template.Must(template.New("admin_separate_login").Parse(adminSeparateLoginHTML))
