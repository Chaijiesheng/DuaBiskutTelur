// Shown on the History tab when there are zero records — for a fresh guest
// session, or an authenticated user who hasn't logged a meal yet.
// One joke/CTA array per supported language — picked randomly by the caller.
export const HISTORY_EMPTY_MESSAGES = {
  en: [
    { message: "📂 History is empty. You haven't uploaded any meals yet... did you expect magic?", cta: "🍔 Let's Fix That" },
    { message: "👀 You haven't uploaded any food photos yet. This page can't invent your history.", cta: '📷 Create Some History' },
    { message: "🫠 No uploads, no history. That's kind of how history works.", cta: '🍽️ Start My Story' },
    { message: '💀 You uploaded exactly 0 meals... and came to the History page with confidence.', cta: '🥲 Fix My Mistake' },
    { message: "🤷 No scans. No history. I'm not sure what plot twist you were expecting.", cta: '🍔 Start the Plot' },
    { message: '🦕 Scientists believe humans eat food. Your history says otherwise.', cta: '🍔 Update the Science' },
    { message: "👻 No meal history found. Either you've never eaten... or you're very good at covering your tracks.", cta: '📸 Expose My Lunch' },
    { message: '📜 Your food history is emptier than my social life.', cta: "🍔 Let's Make History" },
    { message: "🚔 No food records found. Congratulations, you've committed no calorie crimes today.", cta: '🍟 Commit My First Crime' },
  ],
  zh: [
    { message: '📂 历史记录是空的。你还没有上传过任何一餐……难道在等奇迹发生？', cta: '🍔 那就现在开始吧' },
    { message: '👀 你还没有上传过任何食物照片。这个页面变不出历史记录。', cta: '📷 创造一些历史吧' },
    { message: '🫠 没有上传，就没有记录。历史就是这么回事。', cta: '🍽️ 开始我的故事' },
    { message: '💀 你确实上传了 0 餐……却自信满满地来到历史页面。', cta: '🥲 纠正我的失误' },
    { message: '🤷 没有扫描，没有记录。真不知道你在期待什么反转剧情。', cta: '🍔 开始剧情吧' },
    { message: '🦕 科学家相信人类是要吃东西的。你的记录却不这么说。', cta: '🍔 更新一下科学吧' },
    { message: '👻 找不到任何饮食记录。要么你从未吃过东西……要么你很擅长掩盖行踪。', cta: '📸 曝光我的午餐' },
    { message: '📜 你的饮食记录比我的社交生活还要空虚。', cta: '🍔 一起创造历史吧' },
    { message: '🚔 找不到任何饮食记录。恭喜你，今天没有犯下任何“卡路里罪行”。', cta: '🍟 犯下我的第一宗罪' },
  ],
  ms: [
    { message: '📂 Sejarah kosong. Anda belum muat naik sebarang makanan... anda jangkakan keajaiban?', cta: '🍔 Mari Betulkan Itu' },
    { message: '👀 Anda belum muat naik sebarang gambar makanan. Halaman ini tidak boleh mereka-reka sejarah anda.', cta: '📷 Cipta Sedikit Sejarah' },
    { message: '🫠 Tiada muat naik, tiada sejarah. Memang begitulah cara sejarah berfungsi.', cta: '🍽️ Mulakan Kisah Saya' },
    { message: '💀 Anda muat naik tepat 0 makanan... dan datang ke halaman Sejarah dengan penuh keyakinan.', cta: '🥲 Betulkan Kesilapan Saya' },
    { message: '🤷 Tiada imbasan. Tiada sejarah. Saya tak pasti apa lakonan yang anda jangkakan.', cta: '🍔 Mulakan Cerita' },
    { message: '🦕 Saintis percaya manusia makan makanan. Sejarah anda mengatakan sebaliknya.', cta: '🍔 Kemas Kini Sains' },
    { message: '👻 Tiada sejarah makanan dijumpai. Sama ada anda tak pernah makan... atau sangat pandai menyorok jejak.', cta: '📸 Dedahkan Makan Tengah Hari Saya' },
    { message: '📜 Sejarah makanan anda lebih kosong daripada kehidupan sosial saya.', cta: '🍔 Mari Cipta Sejarah' },
    { message: '🚔 Tiada rekod makanan dijumpai. Tahniah, anda tidak melakukan sebarang "jenayah kalori" hari ini.', cta: '🍟 Lakukan Jenayah Pertama Saya' },
  ],
}
