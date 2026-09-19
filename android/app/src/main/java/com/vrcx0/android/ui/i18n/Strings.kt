package com.vrcx0.android.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.vrcx0.android.data.AppLanguage
import com.vrcx0.android.data.LocalAppSettings

/**
 * The active UI language, provided by [VrcxRoot] from the collected settings.
 * Screens read it through [tr] / [trf] rather than importing settings.
 */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ZH }

/**
 * Translates a user-visible literal.
 *
 * Keys are the **Chinese** originals the codebase was written with, so the ZH
 * table is empty by definition -- a missing key in EN/RU degrades to the Chinese
 * text instead of showing a key name, which keeps partial coverage shippable.
 * New strings should be added to both tables below; if you forget, the string
 * still renders (in Chinese) rather than breaking.
 */
@Composable
fun String.tr(): String {
    val lang = LocalAppSettings.current.language
    if (lang == AppLanguage.ZH) return this
    return TRANSLATIONS[lang]?.get(this) ?: this
}

/** Non-composable lookup for callers outside composition (e.g. `remember` blocks). */
fun String.tr(lang: AppLanguage): String {
    if (lang == AppLanguage.ZH) return this
    return TRANSLATIONS[lang]?.get(this) ?: this
}

/**
 * Translates a template. Keys use `%s` placeholders; [args] are substituted in
 * order. Example: `"在线 %s / %s".trf(online, total)`.
 */
@Composable
fun String.trf(vararg args: Any?): String {
    val lang = LocalAppSettings.current.language
    val template = if (lang == AppLanguage.ZH) {
        this
    } else {
        TRANSLATIONS[lang]?.get(this) ?: this
    }
    return format(template, args)
}

/** Non-composable template lookup for callers outside composition. */
fun String.trf(lang: AppLanguage, vararg args: Any?): String {
    val template = if (lang == AppLanguage.ZH) this else TRANSLATIONS[lang]?.get(this) ?: this
    return format(template, args)
}

/** `%s` substitution that never throws on a mismatched arg count. */
private fun format(template: String, args: Array<out Any?>): String {
    var out = template
    for (arg in args) {
        val idx = out.indexOf("%s")
        if (idx < 0) break
        out = out.substring(0, idx) + arg.toString() + out.substring(idx + 2)
    }
    return out
}

private val EN: Map<String, String> = mapOf(
    // tabs & nav
    "动态" to "Feed", "好友" to "Friends", "主页" to "Home", "收藏" to "Favorites",
    "个人" to "Profile", "返回" to "Back", "通知" to "Notifications", "关系图" to "Graph",
    "共同好友关系图" to "Mutual friends graph", "列表" to "List",
    // status & presence
    // ⚠️ The four social-status labels must match the official client's
    // `dialog.user.status.*` strings (see `src/localization/*.json`):
    // join me -> Join Me / 欢迎加入, active -> Online / 在线,
    // ask me -> Ask Me / 忙碌, busy -> Do Not Disturb / 请勿打扰.
    "上线" to "Online", "下线" to "Offline", "在线" to "Online", "离线" to "Offline",
    "在线 —" to "Online —", "隐身" to "Hidden", "活跃" to "Active", "忙碌" to "Busy",
    "请勿打扰" to "Do Not Disturb", "欢迎加入" to "Join Me",
    "空闲" to "Idle", "可加入" to "Join me", "询问我" to "Ask me", "昨天" to "yesterday", "刚刚" to "just now",
    "私密房间" to "Private room", "在私密房间" to "In a private room", "传送中" to "Traveling",
    "另一个世界" to "Another world", "未知世界" to "Unknown world", "未知位置" to "Unknown location",
    "同房间" to "Same room", "非好友" to "Not a friend", "好友请求" to "Friend request",
    "我" to "Me", "某人" to "Someone",
    // trust ranks
    "新手" to "Newcomer", "用户" to "User", "访客" to "Visitor", "基础" to "Basic",
    "熟识" to "Known", "受信任" to "Trusted", "老用户" to "Veteran", "传奇" to "Legendary",
    "信任" to "Trust", "信任等级" to "Trust rank",
    // Official client rank names (per the user's zh/en/ru table)
    "劣迹玩家" to "Nuisance", "游客" to "Visitor", "萌新" to "New User",
    "玩家" to "User", "长期玩家" to "Known User", "资深玩家" to "Trusted User",
    "传奇玩家" to "Legendary User", "好友" to "Friend",
    // feed
    "位置" to "Location", "位置变化" to "Moved", "状态" to "Status", "状态变化" to "Status change",
    "简介" to "Bio", "简介变化" to "Bio change", "模型" to "Avatar", "模型变化" to "Avatar change",
    "世界" to "World", "实例类型" to "Instance type", "来自" to "From", "群组" to "Group",
    "群组实例" to "Group instance", "时间" to "Time", "原状态" to "Previous status",
    "原简介" to "Previous bio", "状态文字" to "Status message", "人称代词" to "Pronouns",
    "还没有动态" to "The feed is empty", "没有匹配的动态" to "No matching feed items",
    "加载更早的动态" to "Load earlier feed", "加载中…" to "Loading…", "加载失败" to "Failed to load",
    "没有通知" to "No notifications", "全部已读" to "Mark all seen",
    "加载资料失败" to "Failed to load profile", "等待服务器…" to "Waiting for the server…",
    "查看用户资料" to "View profile", "查看资料" to "View profile",
    "搜索动态（好友名 / 世界名）" to "Search feed (friend / world)",
    // friends
    "搜索好友" to "Search friends", "还没有好友" to "No friends yet",
    "没有匹配的好友" to "No matching friends", "好友聚集地" to "Where friends are", "最近浏览" to "Recently browsed", "最新动态" to "Latest activity",
    "数据统计" to "Stats", "动态总数" to "Feed events", "好友上线" to "Friend online", "好友下线" to "Friend offline", "换了模型" to "Avatar changes", "添加好友" to "Friend added", "删除好友" to "Friend removed", "好友改名" to "Renames", "信任变化" to "Trust changes", "浏览过的世界" to "Worlds browsed", "累计浏览次数" to "Total views", "浏览 %d 次" to "Browsed %d times",
    "好友上线后会按所在世界聚到这里" to "Online friends gather here by world",
    "当前没有好友在线" to "No friends online right now", "查看全部" to "Show all",
    "收起" to "Show less", "热闹房间" to "Busy rooms", "加入的群组会显示在这里" to "Groups you join appear here",
    "我的群组" to "My groups", "还没有群组" to "No groups yet",
    // favorites
    "正在加载收藏…" to "Loading favorites…", "创建" to "Create",
    "加入" to "Join",
    // profile / settings
    "设置" to "Settings", "外观" to "Appearance", "跟随系统" to "Follow system",
    "浅色" to "Light", "深色" to "Dark", "动态取色" to "Dynamic color",
    "跟随系统壁纸配色（Material You）" to "Wallpaper-based colors (Material You)",
    "显示实例 ID" to "Show instance ID", "在好友所在世界旁显示实例号" to "Show the raw instance number next to a friend's world",
    "隐藏私密动态" to "Hide private feed items", "不在动态里显示私密房间的事件" to "Hide events from private instances in the feed",
    "隐藏设备" to "Hide platform", "不在好友列表里显示 PC / Android 标签" to "Hide PC / Android tags in the friends list",
    "相对时间" to "Relative time", "显示“3 分钟前”而不是具体时间" to "Show \"3 minutes ago\" instead of a clock time",
    "语言" to "Language", "时区" to "Time zone", "跟随系统时区" to "Follow system time zone",
    "账号" to "Account", "退出登录" to "Sign out", "退出" to "Sign out", "退出登录？" to "Sign out?",
    "忘记" to "Forget", "忘记此服务器" to "Forget this server", "忘记此服务器？" to "Forget this server?",
    "会断开当前 VRChat 账号，但保留这台设备在此服务器上的租户。" to
        "Signs out the VRChat account but keeps this device's tenant on the server.",
    "会清除服务器地址、租户凭据和当前会话，需要重新连接和登录。" to
        "Clears the server address, tenant credential and session. You will need to reconnect and sign in again.",
    "取消" to "Cancel", "重试" to "Retry", "验证" to "Verify",
    "当前模型" to "Current avatar", "原模型：" to "Previous avatar: ",
    "我上传的模型" to "My uploaded avatars", "最近使用模型" to "Recent avatars",
    "编辑" to "Edit", "保存" to "Save", "戳一戳" to "Boop", "已戳一戳" to "Booped!",
    // profile detail fields
    "所在世界" to "Current world", "加入时间" to "Joined", "最近登录" to "Last login",
    "最近活动" to "Last active", "关系" to "Relationship", "徽章" to "Badges",
    "用户 ID" to "User ID", "VRChat 链接" to "VRChat link", "复制" to "Copy", "已复制" to "Copied",
    "允许克隆模型" to "Allows avatar cloning", "使用的语言" to "Languages", "是" to "Yes", "否" to "No",
    "展示的群组" to "Represented group", "最后见面" to "Last met", "见面次数" to "Times met",
    "一起游玩" to "Played together", "加好友时间" to "Friended", "账号创建" to "Account created",
    "世界详情" to "World details", "群组详情" to "Group details", "作者" to "By", "我上传的模型" to "My uploaded avatars", "最近使用模型" to "Recent avatars",
    "代表中" to "Representing",
    // mutual graph
    "正在拉取关系图…" to "Fetching graph…", "正在取消…" to "Cancelling…", "已取消" to "Cancelled",
    "该好友已退出关系图共享" to "This friend left graph sharing",
    // login / server
    "连接到你的服务器" to "Connect to your server", "连接" to "Connect",
    "服务器地址" to "Server address", "设备名称" to "Device name",
    "服务器上用这个名字区分设备" to "Distinguishes this device on the server",
    "密码" to "Password", "用户名" to "Username", "账号" to "Account",
    "登录 VRChat 账号" to "Sign in to VRChat", "用用户名密码登录" to "Sign in with username and password",
    "登录并保存凭据" to "Sign in and save credentials", "需要二步验证" to "Two-factor required",
    "验证码" to "Code", "选择账号" to "Choose an account", "邀请码" to "Invite code",
    "这台服务器已经有用户了，需要运维提供的邀请码" to
        "This server already has users; you need the invite code from its operator",
    "这台服务器还没有任何用户，你现在就是第一个" to
        "No users on this server yet — you are the first",
    "尚未连接服务器" to "Not connected to the server yet", "未连接" to "Disconnected",
    "未连接服务器" to "Not connected", "服务器用了自签证书" to "The server uses a self-signed certificate",
    "证书指纹（SPKI SHA-256）" to "Certificate fingerprint (SPKI SHA-256)",
    "隐藏证书指纹" to "Hide certificate fingerprint",
    "只在使用 https 地址时需要。留空即以明文连接局域网服务器。" to
        "Only needed for https addresses. Leave empty to connect to a LAN server in plain HTTP.",
    "创建第一个用户" to "Create the first user",
    "这台服务器还没有任何用户，你现在就是第一个" to "No users on this server yet — you are the first",
    "VRCX-0 服务端保存会话，手机只是显示端" to "The VRCX-0 server keeps the session; the phone is only a display.",
    "平台" to "Platform", "用户" to "User", "其他" to "Other",
    // world / instance detail sheet
    "所在房间" to "Current instance", "房间人数" to "Occupancy", "人数未知" to "Unknown",
    "房间里的人" to "Who is here", "位好友在这里" to "%d friends here",
    "在线人数" to "Players now", "房间容量" to "Capacity", "推荐容量" to "Recommended",
    "容量" to "Capacity", "浏览" to "Visits", "收藏数" to "Favourites",
    "热度" to "Heat", "人气" to "Popularity", "占用" to "Occupants",
    "最多" to "up to", "公开" to "Public", "私密" to "Private",
    "区域" to "Region", "房间类型" to "Room type", "实例" to "Instance",
    "世界标签" to "Tags", "支持平台" to "Platforms", "版本" to "Version",
    "发布时间" to "Published", "创建时间" to "Created", "更新时间" to "Updated",
    "实验室发布" to "Labs release", "发布状态" to "Release status",
    "实验室世界" to "Labs world", "精选" to "Featured", "持久化数据" to "Persistent data",
    "宣传片" to "Trailer", "所属组织" to "Organization",
    "曾来过的好友" to "Friends who have visited", "还没有好友来过这里" to "No friends have been here yet",
    "来过 %d 次" to "Visited %d times", "最近一次" to "Last visit",
    "这里现在有 %d 位好友" to "%d friends are here right now",
    "我的游玩记录" to "My play history", "还没有游玩记录" to "No play history yet",
    "游玩" to "Played", "次" to "times",
    "公开世界" to "Public world", "秘密世界" to "Private world",
    "社区世界" to "Community world", "游戏世界" to "Game world",
    "已发布" to "Published", "未发布" to "Unpublished", "已隐藏" to "Hidden",
    "公开" to "Public", "仅好友" to "Friends only", "仅邀请" to "Invite only",
    "仅邀请+" to "Invite+", "仅好友+" to "Friends+", "群组" to "Group",
    "群组公开" to "Group public", "群组+" to "Group+",
    "PC" to "PC", "安卓" to "Android",
    "世界详情" to "World details", "房间详情" to "Instance details", "详情" to "Details",
    "重试" to "Retry", "刷新" to "Refresh", "正在加载世界详情…" to "Loading world details…",
    "此世界没有描述" to "No description for this world",
    // misc
    "主页世界" to "Home worlds",
    "全部" to "All", "关系" to "Relationship",

    // mutual graph
    "未命名" to "Unnamed",
    "还没有共同好友数据" to "No mutual-friend data yet",
    "拉取一次后，这里会立刻显示上次的结果，不必每次等待" to
        "After one fetch, the last result shows up instantly here — no need to wait each time",
    "开始拉取" to "Start fetch",
    "以下为本地缓存（%s）" to "Local cache from %s",
    "本地缓存" to "Local cache",
    "刚刚" to "just now",
    "%s 分钟前" to "%s min ago",
    "%s 小时前" to "%s h ago",
    "%s 天前" to "%s d ago",
    "%s 位共同好友" to "%s mutual friends",
    "正在解析名字…" to "Resolving names…"
)

private val RU: Map<String, String> = mapOf(
    "动态" to "Лента", "好友" to "Друзья", "主页" to "Главная", "收藏" to "Избранное",
    "个人" to "Профиль", "返回" to "Назад", "通知" to "Уведомления", "关系图" to "Граф",
    "共同好友关系图" to "Граф общих друзей", "列表" to "Список",
    "上线" to "Онлайн", "下线" to "Оффлайн", "在线" to "Онлайн", "离线" to "Оффлайн",
    "在线 —" to "Онлайн —", "隐身" to "Скрытый", "活跃" to "Активен", "忙碌" to "Занят",
    "空闲" to "Ожидает", "可加入" to "Могу присоединиться", "询问我" to "Спросите меня",
    "请勿打扰" to "Не беспокоить", "欢迎加入" to "Присоединиться ко мне",
    "昨天" to "вчера", "刚刚" to "только что",
    "私密房间" to "Приватная комната", "在私密房间" to "В приватной комнате",
    "传送中" to "В пути", "另一个世界" to "Другой мир", "未知世界" to "Неизвестный мир",
    "未知位置" to "Неизвестное место", "同房间" to "Тот же инстанс", "非好友" to "Не друг",
    "好友请求" to "Заявка в друзья", "我" to "Я", "某人" to "Кто-то",
    "新手" to "Новичок", "用户" to "Пользователь", "访客" to "Гость", "基础" to "Базовый",
    "熟识" to "Знакомый", "受信任" to "Надёжный", "老用户" to "Ветеран", "传奇" to "Легенда",
    "信任" to "Доверие", "信任等级" to "Уровень доверия",
    // Official client rank names (per the user's zh/en/ru table)
    "劣迹玩家" to "Неприятный пользователь", "游客" to "Посетитель",
    "萌新" to "Новый пользователь", "玩家" to "Пользователь",
    "长期玩家" to "Известный пользователь", "资深玩家" to "Доверенный пользователь",
    "传奇玩家" to "Легендарный пользователь", "好友" to "Друг",
    "位置" to "Локация", "位置变化" to "Смена локации", "状态" to "Статус",
    "状态变化" to "Смена статуса", "简介" to "О себе", "简介变化" to "Смена описания",
    "模型" to "Аватар", "模型变化" to "Смена аватара", "世界" to "Мир",
    "实例类型" to "Тип инстанса", "来自" to "Из", "群组" to "Группа",
    "群组实例" to "Групповой инстанс", "时间" to "Время", "原状态" to "Прошлый статус",
    "原简介" to "Прошлое описание", "状态文字" to "Текст статуса", "人称代词" to "Местоимения",
    "还没有动态" to "Лента пуста", "没有匹配的动态" to "Нет подходящих записей",
    "加载更早的动态" to "Загрузить более ранние записи", "加载中…" to "Загрузка…",
    "没有通知" to "Нет уведомлений", "全部已读" to "Прочитать все",
    "加载失败" to "Не удалось загрузить", "加载资料失败" to "Не удалось загрузить профиль",
    "等待服务器…" to "Ожидание сервера…", "查看用户资料" to "Открыть профиль",
    "查看资料" to "Открыть профиль", "搜索动态（好友名 / 世界名）" to "Поиск в ленте (друг / мир)",
    "搜索好友" to "Поиск друзей", "还没有好友" to "Пока нет друзей",
    "没有匹配的好友" to "Друзья не найдены", "好友聚集地" to "Где друзья", "最近浏览" to "Недавно просмотрено", "最新动态" to "Последние события",
    "数据统计" to "Статистика", "动态总数" to "События ленты", "好友上线" to "Друг онлайн", "好友下线" to "Друг офлайн", "换了模型" to "Смена аватара", "添加好友" to "Добавлен друг", "删除好友" to "Удалён друг", "好友改名" to "Переименования", "信任变化" to "Смена доверия", "浏览过的世界" to "Просмотрено миров", "累计浏览次数" to "Всего просмотров", "浏览 %d 次" to "Просмотрено %d раз",
    "好友上线后会按所在世界聚到这里" to "Друзья в сети собираются здесь по мирам",
    "当前没有好友在线" to "Сейчас нет друзей в сети", "查看全部" to "Показать все",
    "收起" to "Свернуть", "热闹房间" to "Оживлённые инстансы",
    "加入的群组会显示在这里" to "Ваши группы появятся здесь",
    "我的群组" to "Мои группы", "还没有群组" to "Пока нет групп",
    "正在加载收藏…" to "Загрузка избранного…", "创建" to "Создать", "加入" to "Присоединиться",
    "设置" to "Настройки", "外观" to "Внешний вид", "跟随系统" to "Как в системе",
    "浅色" to "Светлая", "深色" to "Тёмная", "动态取色" to "Динамический цвет",
    "跟随系统壁纸配色（Material You）" to "Цвета из обоев (Material You)",
    "显示实例 ID" to "Показывать ID инстанса",
    "在好友所在世界旁显示实例号" to "Показывать номер инстанса рядом с миром друга",
    "隐藏私密动态" to "Скрывать приватные записи",
    "不在动态里显示私密房间的事件" to "Скрывать события из приватных инстансов в ленте",
    "隐藏设备" to "Скрывать платформу",
    "不在好友列表里显示 PC / Android 标签" to "Скрывать метки PC / Android в списке друзей",
    "相对时间" to "Относительное время",
    "显示“3 分钟前”而不是具体时间" to "Показывать «3 мин. назад» вместо времени",
    "语言" to "Язык", "时区" to "Часовой пояс", "跟随系统时区" to "Как в системе",
    "账号" to "Аккаунт", "退出登录" to "Выйти", "退出" to "Выйти", "退出登录？" to "Выйти?",
    "忘记" to "Забыть", "忘记此服务器" to "Забыть этот сервер", "忘记此服务器？" to "Забыть этот сервер?",
    "会断开当前 VRChat 账号，但保留这台设备在此服务器上的租户。" to
        "Выйти из аккаунта VRChat, сохранив аренду этого устройства на сервере.",
    "会清除服务器地址、租户凭据和当前会话，需要重新连接和登录。" to
        "Очищает адрес сервера, учётные данные аренды и сессию; потребуется заново подключиться и войти.",
    "取消" to "Отмена", "重试" to "Повторить", "验证" to "Подтвердить",
    "当前模型" to "Текущий аватар",
    "我上传的模型" to "Мои загруженные аватары", "最近使用模型" to "Недавние аватары",
    "编辑" to "Изменить", "保存" to "Сохранить", "戳一戳" to "Буп", "已戳一戳" to "Бупнуто!",
    "所在世界" to "Текущий мир", "加入时间" to "Дата регистрации", "最近登录" to "Последний вход",
    "最近活动" to "Последняя активность", "关系" to "Отношения", "徽章" to "Значки",
    "用户 ID" to "ID пользователя", "VRChat 链接" to "Ссылка VRChat",
    "复制" to "Копировать", "已复制" to "Скопировано",
    "允许克隆模型" to "Разрешает клонировать аватар", "使用的语言" to "Языки", "是" to "Да", "否" to "Нет",
    "展示的群组" to "Представленная группа", "最后见面" to "Последняя встреча",
    "见面次数" to "Сколько раз встречались", "一起游玩" to "Время вместе",
    "加好友时间" to "Дата добавления", "账号创建" to "Аккаунт создан",
    "世界详情" to "О мире", "群组详情" to "О группе", "作者" to "Автор", "我上传的模型" to "Мои загруженные аватары", "最近使用模型" to "Недавние аватары",
    "代表中" to "Представляет",
    "正在拉取关系图…" to "Загрузка графа…", "正在取消…" to "Отмена…", "已取消" to "Отменено",
    "该好友已退出关系图共享" to "Друг покинул обмен графом",
    "连接到你的服务器" to "Подключение к серверу", "连接" to "Подключиться",
    "服务器地址" to "Адрес сервера", "设备名称" to "Имя устройства",
    "服务器上用这个名字区分设备" to "Имя устройства на сервере",
    "密码" to "Пароль", "用户名" to "Имя пользователя",
    "登录 VRChat 账号" to "Вход в VRChat", "用用户名密码登录" to "Вход с именем и паролем",
    "登录并保存凭据" to "Войти и сохранить данные", "需要二步验证" to "Требуется 2FA",
    "验证码" to "Код", "选择账号" to "Выберите аккаунт", "邀请码" to "Код приглашения",
    "这台服务器已经有用户了，需要运维提供的邀请码" to
        "На сервере уже есть пользователи; нужен код приглашения от оператора",
    "这台服务器还没有任何用户，你现在就是第一个" to
        "На сервере ещё нет пользователей — вы первый",
    "尚未连接服务器" to "Ещё не подключено к серверу", "未连接" to "Нет подключения",
    "未连接服务器" to "Нет подключения к серверу",
    "服务器用了自签证书" to "Сервер использует самоподписанный сертификат",
    "证书指纹（SPKI SHA-256）" to "Отпечаток сертификата (SPKI SHA-256)",
    "隐藏证书指纹" to "Скрыть отпечаток сертификата",
    "只在使用 https 地址时需要。留空即以明文连接局域网服务器。" to
        "Нужно только для https. Оставьте пустым для обычного подключения по локальной сети.",
    "创建第一个用户" to "Создать первого пользователя",
    "VRCX-0 服务端保存会话，手机只是显示端" to "Сессия хранится на сервере VRCX-0; телефон — только экран.",
    "平台" to "Платформа", "其他" to "Другое", "主页世界" to "Миры главной",
    "全部" to "Все",
    // world / instance detail sheet
    "所在房间" to "Текущий инстанс", "房间人数" to "Наполненность", "人数未知" to "Неизвестно",
    "房间里的人" to "Кто здесь", "位好友在这里" to "%d друзей здесь",
    "在线人数" to "Игроков сейчас", "房间容量" to "Вместимость", "推荐容量" to "Рекомендуемая",
    "容量" to "Вместимость", "浏览" to "Просмотры", "收藏数" to "В избранном",
    "热度" to "Популярность", "人气" to "Популярность", "占用" to "Занято",
    "最多" to "до", "公开" to "Публичный", "私密" to "Приватный",
    "区域" to "Регион", "房间类型" to "Тип комнаты", "实例" to "Инстанс",
    "世界标签" to "Теги", "支持平台" to "Платформы", "版本" to "Версия",
    "发布时间" to "Опубликован", "创建时间" to "Создан", "更新时间" to "Обновлён",
    "实验室发布" to "Релиз в Labs", "发布状态" to "Статус релиза",
    "实验室世界" to "Мир Labs", "精选" to "Избранный", "持久化数据" to "Постоянные данные",
    "宣传片" to "Трейлер", "所属组织" to "Организация",
    "曾来过的好友" to "Друзья, которые здесь были",
    "还没有好友来过这里" to "Здесь ещё не было друзей",
    "来过 %d 次" to "Был %d раз", "最近一次" to "Последний раз",
    "这里现在有 %d 位好友" to "Здесь сейчас %d друзей",
    "我的游玩记录" to "Моя история", "还没有游玩记录" to "Истории пока нет",
    "游玩" to "В игре", "次" to "раз",
    "公开世界" to "Публичный мир", "秘密世界" to "Приватный мир",
    "社区世界" to "Сообщество", "游戏世界" to "Игровой мир",
    "已发布" to "Опубликован", "未发布" to "Не опубликован", "已隐藏" to "Скрыт",
    "仅好友" to "Только друзья", "仅邀请" to "Только по приглашению",
    "仅邀请+" to "Invite+", "仅好友+" to "Friends+", "群组" to "Группа",
    "群组公开" to "Группа: публичный", "群组+" to "Group+",
    "PC" to "PC", "安卓" to "Android",
    "世界详情" to "О мире", "房间详情" to "Об инстансе", "详情" to "Подробности",
    "重试" to "Повторить", "刷新" to "Обновить",
    "正在加载世界详情…" to "Загрузка сведений о мире…",
    "此世界没有描述" to "У этого мира нет описания",

    // mutual graph
    "未命名" to "Без имени",
    "还没有共同好友数据" to "Пока нет данных об общих друзьях",
    "拉取一次后，这里会立刻显示上次的结果，不必每次等待" to
        "После первой загрузки результат будет показываться сразу — ждать каждый раз не нужно",
    "开始拉取" to "Начать загрузку",
    "以下为本地缓存（%s）" to "Локальный кэш от %s",
    "本地缓存" to "Локальный кэш",
    "刚刚" to "только что",
    "%s 分钟前" to "%s мин назад",
    "%s 小时前" to "%s ч назад",
    "%s 天前" to "%s дн назад",
    "%s 位共同好友" to "%s общих друзей",
    "正在解析名字…" to "Определяем имена…"
)

private val TRANSLATIONS: Map<AppLanguage, Map<String, String>> = mapOf(
    AppLanguage.EN to EN,
    AppLanguage.RU to RU
)
