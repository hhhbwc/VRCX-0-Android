/* eslint-disable */
/* VRChat 内置 boop emoji —— **脚本生成，禁手改**（共 65 项）。
   生成脚本：android-app/tools/gen_boop_emojis.py
   来源：桌面版 VRCX-0 `src/src/shared/constants/vrchatDefaultEmojis.ts`
   id 规则 = `default_` + 名称空格转下划线 + 小写（撇号保留，见 Can't see）；
   预览图 = `https://wiki-files.vrchat.com/<previewFile>`。

   为什么必须抄这份表：服务端**没有**“列出可用 boop emoji”的端点
   （2026-09-29 全仓确认），只有一个 `boop_send(user_id, emoji_id, inventory_item_id)`。
   凭空编 emoji id 就是一例新的静默失效：服务端不报错，对方就是收不到。*/
(function () {
  'use strict';

  var CATALOG = [
  { id: "default_angry", name: "Angry", previewFile: "Angry.webp" },
  { id: "default_blushing", name: "Blushing", previewFile: "Blush.webp" },
  { id: "default_crying", name: "Crying", previewFile: "Crying.webp" },
  { id: "default_frown", name: "Frown", previewFile: "Frown.webp" },
  { id: "default_hand_wave", name: "Hand Wave", previewFile: "Handwave.webp" },
  { id: "default_hang_ten", name: "Hang Ten", previewFile: "Summer_Hangten.webp" },
  { id: "default_in_love", name: "In Love", previewFile: "Inlove.webp" },
  { id: "default_jack_o_lantern", name: "Jack O Lantern", previewFile: "Fall_Jackolantern.webp" },
  { id: "default_kiss", name: "Kiss", previewFile: "Kiss.webp" },
  { id: "default_laugh", name: "Laugh", previewFile: "Laugh.webp" },
  { id: "default_skull", name: "Skull", previewFile: "Fall_Skull.webp" },
  { id: "default_smile", name: "Smile", previewFile: "Smile.webp" },
  { id: "default_spooky_ghost", name: "Spooky Ghost", previewFile: "Fall_Ghost.webp" },
  { id: "default_stoic", name: "Stoic", previewFile: "Stoic.webp" },
  { id: "default_sunglasses", name: "Sunglasses", previewFile: "Sunglasses.webp" },
  { id: "default_thinking", name: "Thinking", previewFile: "Thinking.webp" },
  { id: "default_thumbs_down", name: "Thumbs Down", previewFile: "Dislike.webp" },
  { id: "default_thumbs_up", name: "Thumbs Up", previewFile: "Like.webp" },
  { id: "default_tongue_out", name: "Tongue Out", previewFile: "Tongue.webp" },
  { id: "default_wow", name: "Wow", previewFile: "Wow.webp" },
  { id: "default_arrow_point", name: "Arrow Point", previewFile: "Accessibility_Arrow.webp" },
  { id: "default_can't_see", name: "Can't see", previewFile: "Accessibility_Blind.webp" },
  { id: "default_hourglass", name: "Hourglass", previewFile: "Accessibility_Hourglass.webp" },
  { id: "default_keyboard", name: "Keyboard", previewFile: "Accessibility_Keyboard.webp" },
  { id: "default_no_headphones", name: "No Headphones", previewFile: "Accessibility_Deafened.webp" },
  { id: "default_no_mic", name: "No Mic", previewFile: "Accessibility_Muted.webp" },
  { id: "default_portal", name: "Portal", previewFile: "Accessibility_Portal.webp" },
  { id: "default_shush", name: "Shush", previewFile: "Accessibility_Shush.webp" },
  { id: "default_bats", name: "Bats", previewFile: "Fall_Bat.webp" },
  { id: "default_cloud", name: "Cloud", previewFile: "Cloud.webp" },
  { id: "default_fire", name: "Fire", previewFile: "Fire.webp" },
  { id: "default_snow_fall", name: "Snow Fall", previewFile: "Winter_Snowflake.webp" },
  { id: "default_snowball", name: "Snowball", previewFile: "Snowball_-_emoji_animation_type.gif" },
  { id: "default_splash", name: "Splash", previewFile: "Summer_Splash.webp" },
  { id: "default_web", name: "Web", previewFile: "Fall_Web.webp" },
  { id: "default_beer", name: "Beer", previewFile: "Beer.webp" },
  { id: "default_candy", name: "Candy", previewFile: "Fall_Candy.webp" },
  { id: "default_candy_cane", name: "Candy Cane", previewFile: "Winter_Candycane.webp" },
  { id: "default_candy_corn", name: "Candy Corn", previewFile: "Fall_CandyCorn.webp" },
  { id: "default_champagne", name: "Champagne", previewFile: "Winter_Champagneclink.webp" },
  { id: "default_drink", name: "Drink", previewFile: "Summer_Coconut_Drink.webp" },
  { id: "default_gingerbread", name: "Gingerbread", previewFile: "Winter_Gingerbreadman.webp" },
  { id: "default_ice_cream", name: "Ice Cream", previewFile: "Summer_Icecream.webp" },
  { id: "default_pineapple", name: "Pineapple", previewFile: "Summer_Pineapple.webp" },
  { id: "default_pizza", name: "Pizza", previewFile: "Pizza.webp" },
  { id: "default_tomato", name: "Tomato", previewFile: "Tomato.webp" },
  { id: "default_beachball", name: "Beachball", previewFile: "Summer_Beachball.webp" },
  { id: "default_coal", name: "Coal", previewFile: "Winter_Coal.webp" },
  { id: "default_confetti", name: "Confetti", previewFile: "Winter_ConfettiPopper.webp" },
  { id: "default_gift", name: "Gift", previewFile: "Gift.webp" },
  { id: "default_gifts", name: "Gifts", previewFile: "Winter_Gifts.webp" },
  { id: "default_life_ring", name: "Life Ring", previewFile: "Lifering.webp" },
  { id: "default_mistletoe", name: "Mistletoe", previewFile: "Winter_Mistletoe.webp" },
  { id: "default_money", name: "Money", previewFile: "Money.webp" },
  { id: "default_neon_shades", name: "Neon Shades", previewFile: "Summer_Neonshades.webp" },
  { id: "default_sun_lotion", name: "Sun Lotion", previewFile: "Summer_Sunlotion.webp" },
  { id: "default_boo", name: "Boo", previewFile: "Fall_BOO.webp" },
  { id: "default_broken_heart", name: "Broken Heart", previewFile: "Brokenheart.webp" },
  { id: "default_exclamation", name: "Exclamation", previewFile: "Exclaim.webp" },
  { id: "default_go", name: "Go", previewFile: "Go.webp" },
  { id: "default_heart", name: "Heart", previewFile: "Love.webp" },
  { id: "default_music_note", name: "Music Note", previewFile: "Music.webp" },
  { id: "default_question", name: "Question", previewFile: "Question.webp" },
  { id: "default_stop", name: "Stop", previewFile: "Stop.webp" },
  { id: "default_zzz", name: "Zzz", previewFile: "ZZZZ.webp" }
  ];

  var BASE = 'https://wiki-files.vrchat.com/';

  window.VRCX_DEFAULT_EMOJIS = CATALOG.map(function (it) {
    return { id: it.id, name: it.name, previewUrl: BASE + it.previewFile };
  });
})();
