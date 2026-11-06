import Link from "next/link";

const highlights = [
  { number: "01", title: "老师布置更轻松", text: "集中管理班级与作业，让每一次布置都有清晰的节奏。" },
  { number: "02", title: "学生提交不迷路", text: "待完成、已提交与反馈一目了然，专注把作业做好。" },
  { number: "03", title: "过程清楚可追踪", text: "围绕作业协作，保留重要节点，减少反复沟通。" },
];

export default function Home() {
  return (
    <main className="landing-page">
      <header className="landing-nav page-wrap">
        <Link href="/" className="brand" aria-label="作业伴首页">
          <span className="brand-mark">作</span>
          <span>作业伴</span>
        </Link>
        <nav className="landing-actions" aria-label="账号入口">
          <Link href="/login" className="text-link">登录</Link>
          <Link href="/register" className="button button-primary button-small">学生注册</Link>
        </nav>
      </header>

      <section className="hero page-wrap">
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-dot" />为课堂，也为每一次进步</p>
          <h1>让作业成为<br /><em>更好的连接。</em></h1>
          <p className="hero-lede">作业伴把布置、提交与反馈放在一个清晰的空间里，陪老师和学生把每一步走得更从容。</p>
          <aside className="demo-banner landing-demo-banner" role="note">
            <span className="demo-badge">免费演示版</span>
            <p><strong>首发暂不开放附件上传。</strong>免费演示版仅使用虚构数据，未接入附件存储，附件下载也暂不可用。</p>
          </aside>
          <div className="hero-actions">
            <Link href="/login" className="button button-primary">进入作业伴 <span aria-hidden="true">→</span></Link>
            <Link href="/register" className="button button-secondary">我是学生，先注册</Link>
          </div>
          <p className="hero-note"><span className="note-check" aria-hidden="true">✓</span> 简单开始，按角色进入你的作业空间</p>
        </div>
        <div className="hero-art" aria-hidden="true">
          <div className="art-orbit orbit-one" />
          <div className="art-orbit orbit-two" />
          <div className="art-card art-card-main">
            <div className="art-card-top"><span className="mini-brand">作</span><span className="art-muted">我的作业</span><span className="art-more">···</span></div>
            <div className="art-greeting">下午好，继续加油 <span>✦</span></div>
            <div className="art-progress-label"><span>本周进度</span><strong>68%</strong></div>
            <div className="art-progress"><i /></div>
            <div className="art-list"><div><span className="list-dot green" /><span>数学 · 一元二次方程</span><b>已完成</b></div><div><span className="list-dot orange" /><span>语文 · 阅读练习</span><b className="todo">待完成</b></div><div><span className="list-dot blue" /><span>英语 · 单元复习</span><b>已提交</b></div></div>
          </div>
          <div className="art-card art-card-float"><span className="float-icon">✓</span><div><strong>做得很好</strong><small>你的作业已提交</small></div></div>
          <div className="art-spark spark-one">✦</div><div className="art-spark spark-two">✧</div>
        </div>
      </section>

      <section className="value-section page-wrap" aria-label="作业伴的特点">
        {highlights.map((item) => <article className="value-item" key={item.number}><span className="value-number">{item.number}</span><h2>{item.title}</h2><p>{item.text}</p></article>)}
      </section>
      <footer className="landing-footer page-wrap"><span>作业伴 · 让学习协作更自然</span><span>从今天开始，认真完成每一份作业。</span></footer>
    </main>
  );
}
