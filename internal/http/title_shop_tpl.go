package httpapi

import "html/template"

type titleShopData struct {
	Error       string
	Success     string
	LoggedIn    bool
	Token       string
	UID         string
	UserTitle   string
	CoinBalance int
	Titles      []any
}

const titleShopHTML = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>旧聊称号商店</title>
  <style>
    *{box-sizing:border-box;border-radius:0!important}
    body{max-width:720px;margin:0 auto;padding:24px;font-family:-apple-system,"Noto Sans SC",sans-serif;line-height:1.7;color:#111;background:#fff}
    h1{font-size:26px;margin:4px 0}
    h3{margin:0 0 6px}
    a{color:#0366d6;text-decoration:none}
    input,button{padding:8px 12px;border:1px solid #999;font-size:14px;margin:2px}
    button{cursor:pointer;background:#111;color:#fff;border-color:#111}
    .row{margin:8px 0}
    .card{border:1px solid #ccc;padding:16px;margin:10px 0}
    .ok{color:#0a7a0a}.err{color:#c00}
  </style>
</head>
<body>
  <p><a href="/">HOME</a> / <a href="/download/oldchat.apk">DOWNLOAD</a> / <a href="/igotbanned">APPEAL</a></p>
  <h1>旧聊称号商店</h1>
  <p>购买专属称号。更换称号自动退还上一枚称号的购买旧币。</p>

  {{if .LoggedIn}}
  <div class="row">账号：{{.UID}} ｜ 余额：<strong>{{.CoinBalance}}</strong> 旧币 ｜ 当前称号：<strong>{{if .UserTitle}}{{.UserTitle}}{{else}}无{{end}}</strong></div>
  <div class="row"><a href="/shop/logout">退出登录</a></div>
  {{else}}
  <div class="err">请先登录后购买。</div>
  <form method="post" action="/shop/login">
    <input name="identifier" placeholder="账号 / 邮箱">
    <input name="password" type="password" placeholder="密码">
    <button type="submit">登录</button>
  </form>
  {{end}}

  {{if .Success}}<div class="ok">{{.Success}}</div>{{end}}
  {{if .Error}}<div class="err">{{.Error}}</div>{{end}}

  <h3>称号列表</h3>
  {{range .Titles}}
  <div class="card">
    {{.Title}} ｜ 价格：{{.Price}} 旧币
    {{if $.LoggedIn}}
    <form method="post" action="/report" style="display:inline;">
      <input type="hidden" name="title_id" value="{{.ID}}">
      <input type="hidden" name="token" value="{{$.Token}}">
      <button type="submit">购买</button>
    </form>
    {{else}}
    <button type="button" disabled>请先登录</button>
    {{end}}
  </div>
  {{else}}
  <div class="card">暂无可购买称号</div>
  {{end}}

  <div class="card">
    <h3>自定义称号</h3>
    <div>定价：200 旧币 / 个（全服唯一）</div>
    {{if .LoggedIn}}
    <form method="post" action="/report">
      <input name="custom_title" placeholder="输入自定义称号（不超过20字）">
      <button type="submit">购买</button>
    </form>
    {{else}}
    <div>请先登录后购买</div>
    {{end}}
  </div>

  <p style="color:#666;font-size:13px;">购买后称号立即生效；同名称号不可重复购买。</p>
</body>
</html>
`

var titleShopTpl = template.Must(template.New("title_shop").Parse(titleShopHTML))
