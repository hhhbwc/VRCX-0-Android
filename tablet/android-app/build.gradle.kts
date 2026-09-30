// 真机预览壳（开发期）—— 不再是"平板模式客户端"，也不是第二个产品。
// 界面与手机端是同一套（同一份 DOM、同一套 M3 令牌），平板只是容器变宽后的自然重排；
// 正式交付时并进手机端 App (com.vrcx0.android) 的一个尺寸档，本壳退役。
// 这里只声明 AGP；本模块是纯 Java + 一个 WebView，不引 Compose / AndroidX，
// 依赖面最小，构建最不容易被镜像问题绊住。
plugins {
    id("com.android.application") version "8.7.3" apply false
}
