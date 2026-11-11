package httpapi

import (
	"net/http"
)

// handleTermsPage 返回《OldChat（旧聊）用户服务协议与免责声明》网页（/terms, /agreement, /privacy）
func (a *API) handleTermsPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(termsHTML))
}

const termsHTML = `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>用户服务协议与免责声明 · 旧聊 (OldChat)</title>
  <style>
    :root {
      --bg: #0d1117;
      --card-bg: #161b22;
      --border: #30363d;
      --text: #c9d1d9;
      --text-heading: #f0f6fc;
      --text-muted: #8b949e;
      --primary: #238636;
      --accent: #58a6ff;
      --danger: #f85149;
      --warning: #d29922;
      --danger-bg: rgba(248, 81, 73, 0.1);
      --warning-bg: rgba(210, 153, 34, 0.1);
      --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
      --font-mono: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background: var(--bg);
      color: var(--text);
      font-family: var(--font-sans);
      line-height: 1.75;
      padding: 30px 16px 80px;
    }
    .container {
      max-width: 880px;
      margin: 0 auto;
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 36px 32px;
    }
    .header {
      border-bottom: 1px solid var(--border);
      padding-bottom: 20px;
      margin-bottom: 24px;
    }
    .header h1 {
      font-size: 24px;
      font-weight: 700;
      color: var(--text-heading);
      margin-bottom: 8px;
    }
    .meta {
      font-size: 13px;
      color: var(--text-muted);
      display: flex;
      gap: 16px;
      flex-wrap: wrap;
    }
    .meta-tag {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      background: rgba(88, 166, 255, 0.15);
      color: var(--accent);
      font-weight: 600;
      font-size: 12px;
    }
    .alert-box {
      background: var(--danger-bg);
      border: 1px solid var(--danger);
      border-radius: 6px;
      padding: 16px 20px;
      margin-bottom: 28px;
      font-size: 14px;
    }
    .alert-box strong {
      color: var(--danger);
      display: block;
      font-size: 15px;
      margin-bottom: 6px;
    }
    .warning-box {
      background: var(--warning-bg);
      border: 1px solid var(--warning);
      border-radius: 6px;
      padding: 16px 20px;
      margin-top: 30px;
      font-size: 14px;
    }
    .warning-box strong {
      color: var(--warning);
      display: block;
      font-size: 15px;
      margin-bottom: 6px;
    }
    h2 {
      font-size: 18px;
      font-weight: 700;
      color: var(--text-heading);
      margin: 28px 0 12px;
      padding-bottom: 6px;
      border-bottom: 1px solid var(--border);
    }
    h3 {
      font-size: 15px;
      font-weight: 600;
      color: var(--accent);
      margin: 16px 0 6px;
    }
    p {
      margin-bottom: 12px;
      font-size: 14px;
    }
    ul, ol {
      margin: 8px 0 14px 24px;
      font-size: 14px;
    }
    li {
      margin-bottom: 6px;
    }
    b, strong {
      color: var(--text-heading);
    }
    .footer-sign {
      margin-top: 36px;
      padding-top: 20px;
      border-top: 1px solid var(--border);
      text-align: right;
      font-size: 13px;
      color: var(--text-muted);
      line-height: 1.8;
    }
    .footer-sign b {
      color: var(--text);
      display: block;
      font-size: 15px;
    }
    a {
      color: var(--accent);
      text-decoration: none;
    }
    a:hover {
      text-decoration: underline;
    }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <span class="meta-tag">法律合规声明</span>
      <h1>OldChat（旧聊）用户服务协议与免责声明</h1>
      <div class="meta">
        <span>最后更新日期：2026年8月16日</span>
        <span>生效日期：2026年8月16日</span>
        <span>服务性质：免费非营利公益项目</span>
      </div>
    </div>

    <div class="alert-box">
      <strong>特别提示</strong>
      <p>欢迎使用 OldChat（以下简称“OldChat”“本软件”或“本服务”）。</p>
      <p>OldChat目前是由<b>个人开发者独立开发、维护和运营的免费、非营利性公益软件项目</b>，目前不向用户收取使用费，不提供付费会员、付费增值服务或平台内商业交易服务。OldChat的相关服务器及技术基础设施主要位于香港特别行政区或其他中国大陆境外地区。</p>
      <p>在注册、登录、下载、安装、访问或以其他方式使用本服务前，请您认真阅读并充分理解本协议，特别是涉及<b>服务范围与地域限制、用户行为规范、账号及数据风险、内容处理、服务中断、责任限制、用户赔偿责任、协议变更以及法律适用和争议解决</b>等与您具有重大利害关系的条款。</p>
      <p>对于前述重要条款，OldChat将通过加粗、弹窗、单独提示、勾选确认或其他合理方式提醒您注意。</p>
      <p style="margin-bottom:0;color:var(--danger);font-weight:700;">如您不同意本协议，请停止注册、下载、访问或使用本服务。您完成注册、点击“同意”、登录或继续使用本服务，即表示您已阅读、理解并同意接受本协议。</p>
    </div>

    <h2>第一条 定义及服务主体</h2>
    <h3>1.1 OldChat</h3>
    <p>“OldChat”“本软件”或“本服务”是指由个人开发者提供的 OldChat 软件、客户端、网站、服务器接口、即时通信功能、文件传输功能以及与上述功能相关的技术服务。</p>
    <h3>1.2 运营者</h3>
    <p>“运营者”是指目前实际开发、维护和运营 OldChat 的个人开发者。</p>
    <p>OldChat目前<b>不是由独立法人公司运营的商业平台</b>，运营者目前以个人身份维护本项目。除非相关页面另有明确说明，本协议中的“运营者”不代表任何不存在的公司、法人、关联公司或其他独立法律主体。</p>
    <h3>1.3 用户</h3>
    <p>“用户”是指访问、下载、安装、注册、登录或者以其他方式使用本服务的自然人或其他主体。</p>

    <h2>第二条 服务性质及基本原则</h2>
    <h3>2.1 免费公益项目</h3>
    <p>OldChat目前为免费、非营利性项目，运营者不以向用户收取服务费用为主要目的。免费使用不意味着运营者对服务的连续性、永久性、完整性、安全性或特定用途作出保证。</p>
    <h3>2.2 技术服务性质</h3>
    <p>OldChat主要提供即时通信、信息传输、文件传输及相关技术功能。用户通过本服务主动发送、上传、分享或传输的内容，由用户自行产生或提供。除法律法规另有规定、用户主动触发相关功能或者本协议另有明确约定外，运营者原则上不会主动编辑、修改或改变用户内容的原始意思。</p>
    <h3>2.3 用户内容独立责任</h3>
    <p>用户应当对其通过本服务实施的行为以及由此产生的法律后果承担相应责任。用户不得以“OldChat只是提供技术服务”为由，将依法应由用户本人承担的违法、违约或侵权责任转嫁给运营者。运营者不对用户内容的真实性、准确性、完整性、合法性、权属情况或适用性作出保证。</p>
    <h3>2.4 不构成内容背书</h3>
    <p>用户发布、发送、上传或者传播的任何内容，不代表运营者的立场、观点、意见或政策。运营者不会因用户能够使用本服务传输某一内容，而被视为认可、推荐、支持或保证该内容。</p>

    <h2>第三条 服务地区及跨地区访问</h2>
    <h3>3.1 主要服务地区</h3>
    <p>OldChat目前主要面向<b>香港特别行政区以及中国大陆以外的其他国家和地区</b>提供免费技术服务。OldChat目前<b>不以中华人民共和国大陆地区作为主动推广、广告投放、商业营销或定向经营的主要目标市场</b>。</p>
    <h3>3.2 互联网跨地域特性</h3>
    <p>互联网服务具有开放性和跨地域性。由于网络环境、第三方分发、用户自行获取安装包、链接传播、代理网络以及其他运营者无法完全控制的因素，运营者无法保证能够阻止任何特定地区的用户访问本服务。对于用户主动从其所在地区访问或使用OldChat的行为，用户应自行了解并遵守其所在地适用的法律法规。</p>
    <h3>3.3 地区限制</h3>
    <p>运营者有权根据：适用法律法规、监管要求、网络环境、安全风险、技术条件、服务能力、运营政策，对特定国家或地区的注册、下载、登录、访问、通信或其他功能进行限制、暂停或终止。</p>
    <h3>3.4 不构成规避法律</h3>
    <p>本协议有关服务地区的约定，是对OldChat当前实际运营范围和运营策略的说明，不构成通过合同、服务器位置或者技术措施排除任何依法适用的强制性法律义务。</p>

    <h2>第四条 用户资格及账号安全</h2>
    <h3>4.1 用户资格</h3>
    <p>用户应具备与使用本服务相适应的民事行为能力。未成年人使用本服务的，应当在其监护人的同意、指导和监督下使用。监护人应当依法承担相应的监护责任。</p>
    <h3>4.2 注册信息</h3>
    <p>用户应提供真实、准确、合法的信息，并不得冒用他人身份或使用虚假身份实施违法活动。如注册信息发生重大变化，用户应及时进行更新。</p>
    <h3>4.3 账号安全</h3>
    <p>用户应自行妥善保管账号、密码、验证码以及其他身份验证信息。除因运营者自身原因导致的安全事故外，因用户主动泄露账号信息、验证码、使用不安全设备、安装恶意软件或者其他用户自身原因产生的账号被盗、信息泄露或其他损失，由用户自行承担相应风险。</p>
    <h3>4.4 账号不得用于违法活动</h3>
    <p>用户不得出售、出租、出借、共享、转让账号，也不得利用账号从事诈骗、垃圾信息传播、恶意营销、批量注册、网络攻击或其他违法违规活动。</p>

    <h2>第五条 用户行为规范</h2>
    <p>用户使用OldChat时，应遵守其所在地及其他适用于其行为的法律法规，并尊重他人的合法权益。用户不得利用OldChat制作、存储、发送、上传、传播或实施以下内容或行为：</p>
    <ol>
      <li><b>违法犯罪</b>：实施诈骗、敲诈勒索、洗钱、非法集资、赌博、传销、非法交易、毒品交易、非法买卖枪支弹药或其他违法犯罪活动。</li>
      <li><b>色情及危害未成年人</b>：传播法律禁止的淫秽色情内容，或者制作、传播涉及未成年人的性剥削、性虐待、诱导、猥亵及其他违法有害内容。</li>
      <li><b>人身权及个人资料侵害</b>：侮辱、诽谤、威胁、骚扰他人，实施人肉搜索、非法披露他人个人资料、恶意散播私人信息或以其他方式侵犯他人的隐私、名誉、肖像及其他合法权益。</li>
      <li><b>知识产权侵权</b>：未经合法授权上传、传播或分享侵犯他人著作权、商标权、专利权、商业秘密及其他知识产权的内容。</li>
      <li><b>网络攻击</b>：制作或传播病毒、木马、勒索软件及其他恶意程序，不得实施DDoS攻击、扫描、入侵、漏洞利用、接口轰炸或其他破坏网络安全的行为。</li>
      <li><b>系统滥用</b>：不得通过自动化程序、爬虫、脚本或其他技术手段恶意批量注册账号、抓取大量平台数据、绕过访问限制、破解安全机制或干扰OldChat正常运行。</li>
      <li><b>冒充及欺诈</b>：不得冒充政府机关、社会组织、企业、公众人物、OldChat运营者或其他个人实施欺骗行为。</li>
      <li><b>其他违法或侵权行为</b>：不得实施任何违反适用法律法规、侵犯他人合法权益或者严重影响OldChat正常运行和网络安全的行为。</li>
    </ol>

    <h2>第六条 用户内容及平台处置</h2>
    <h3>6.1 用户内容责任</h3>
    <p>用户应保证其发送、上传、传播或存储的内容来源合法，并在必要情况下取得相关权利人的授权。因用户内容引发的第三方投诉、索赔、行政调查、诉讼、仲裁或其他纠纷，由相关责任用户依法承担相应责任。</p>
    <h3>6.2 举报及投诉</h3>
    <p>用户或权利人可以通过OldChat公布的举报、投诉或联系方式提交涉嫌违法、侵权或其他安全风险的信息。</p>
    <h3>6.3 平台处置权</h3>
    <p>在法律允许的范围内，运营者可以根据用户违反本协议、涉嫌违法或侵权、用户举报、有权机构合法要求、网络安全风险等采取删除、屏蔽、限制传播、限制功能、暂停账号、终止账号、限制设备访问或其他合理措施。</p>
    <h3>6.4 紧急处置与账号终止</h3>
    <p>对于存在明显违法风险、严重安全风险、涉嫌犯罪、恶意攻击或其他紧急情况的，运营者可以在合理范围内先行采取措施。用户严重违规的，运营者有权暂停或者终止全部或部分服务。</p>

    <h2>第七条 虚拟权益及其他功能</h2>
    <p>如果OldChat未来增加积分、虚拟道具、数字权益、会员或其他功能，其使用条件、有效期、退款及终止规则将通过相应产品规则另行公布。未经另行公布并实际提供相关功能，本协议不产生任何付费会员、储值、虚拟货币或其他商业服务承诺。</p>

    <h2>第八条 知识产权</h2>
    <p>OldChat的软件程序、名称、标识、界面、源代码等知识产权归相应权利人所有。用户对其合法上传的内容依法享有相应权利，并授予运营者为实现本服务正常运行所必需的技术处理权限。</p>

    <h2>第九条 服务可用性及数据风险</h2>
    <h3>9.1 按现状提供</h3>
    <p>在法律允许的最大范围内，OldChat按照现有技术条件提供服务。运营者不保证服务始终连续、稳定或及时，不保证不存在程序错误或安全风险，不保证所有消息即时送达或永久保存，不保证永久兼容所有设备和操作系统。</p>
    <h3>9.2 数据自行备份</h3>
    <p><b>用户应自行备份重要聊天记录、文件、图片及其他重要资料。</b>除法律规定或运营者另有明确承诺外，OldChat不承诺永久保存或永久恢复用户数据。</p>
    <h3>9.3 不可抗力及第三方原因</h3>
    <p>因自然灾害、战争、社会事件、政府措施、通信基础设施故障、网络攻击、云服务故障或其他运营者无法合理控制的原因导致服务中断、延迟或数据异常的，运营者在法律允许的范围内不承担相应责任。</p>

    <h2>第十条 最大合法范围内的责任限制</h2>
    <p>在适用法律允许的最大范围内，运营者不对用户因自身行为、违反协议、用户间纠纷、设备网络问题、第三方行为、未自行备份数据、服务维护升级或不可抗力等产生的间接损失、预期利益损失、商业机会损失承担责任。本协议不排除法律规定不得排除或限制的责任（包括故意、重大过失或法定人身损害责任）。</p>

    <h2>第十一条 用户赔偿及责任追偿</h2>
    <p>如因用户故意或过失违反本协议、违反适用法律法规或侵犯第三方合法权益，导致运营者依法承担第三方赔偿责任或产生其他直接实际损失，运营者有权在法律允许的范围内向相关责任用户追偿实际发生的合理损失及必要维权费用。</p>

    <h2>第十二条 个人信息及隐私</h2>
    <p>运营者将通过单独的《OldChat隐私政策》说明个人信息的收集、使用、存储、处理及保护方式。运营者仅在提供、维护和保障本服务正常运行所必需的范围内依法处理数据，并采取合理安全措施。无合法依据的情况下绝不出售个人信息。</p>

    <h2>第十三条 数据及执法协助</h2>
    <p>如运营者依法收到司法机关、执法机关、监管机构或其他有权机构的合法要求，运营者将在适用法律要求和允许的范围内提供相应协助。运营者不会承诺在任何情况下拒绝合法执法机关的数据请求。</p>

    <h2>第十四条 协议变更</h2>
    <p>运营者可能因法律法规变化、监管要求、技术升级或服务调整修改本协议。涉及用户重大权利义务的实质性修改将通过公告、弹窗或应用内通知提示。用户在修改后继续使用的视为接受修改后的协议。</p>

    <h2>第十五条 服务终止</h2>
    <p>用户可随时停止使用。由于OldChat属于个人维护的公益项目，运营者<b>不承诺项目永久持续运营</b>，可根据维护能力、技术条件或法律法规暂停或终止服务。</p>

    <h2>第十六条 法律适用及争议解决</h2>
    <p>在不违反用户所在地具有强制适用效力的法律规定的前提下，本协议原则上适用<b>香港特别行政区法律</b>。因本协议产生的争议双方应首先协商解决，协商不成的，在法律允许范围内提交香港特别行政区有管辖权的法院处理。</p>

    <h2>第十七条 条款独立性</h2>
    <p>如本协议任何条款被认定为无效或不可执行，不影响其他条款的效力，相关条款应调整为最接近原条款目的且合法有效的内容。</p>

    <h2>第十八条 完整协议及其他</h2>
    <p>本协议与《隐私政策》、专项产品规则共同构成用户使用OldChat的基本规则。</p>

    <div class="warning-box">
      <strong>【重要条款特别提示】请用户特别注意：</strong>
      <ol>
        <li>OldChat目前为个人开发者维护的免费、非营利性公益项目，不向用户收取使用费。</li>
        <li>OldChat服务器及部分技术基础设施主要位于香港或其他中国大陆境外地区。</li>
        <li>OldChat目前主要面向香港及中国大陆以外地区提供服务，并不以中国大陆作为主动推广、广告投放或定向经营的主要目标市场。</li>
        <li>由于互联网服务的跨地域特性，运营者无法完全阻止其他地区用户自主访问本服务；用户应自行确认其所在地区使用本服务的合法性。</li>
        <li>用户应对自己通过OldChat发送、上传、传播或存储的内容及自身行为承担相应法律责任。</li>
        <li>在法律允许的最大范围内，OldChat不保证服务永久运行、持续在线、完全无错误或永久保存用户数据。</li>
        <li>本协议不排除法律规定不得排除或限制的责任。</li>
        <li>用户应同时阅读《OldChat隐私政策》。</li>
      </ol>
    </div>

    <div class="footer-sign">
      <b>OldChat（旧聊）</b>
      <span>个人开发者 / 项目运营者</span><br/>
      <span>版本：2026年8月16日</span>
    </div>
  </div>
</body>
</html>
`
