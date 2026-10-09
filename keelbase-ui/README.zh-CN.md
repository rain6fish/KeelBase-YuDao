# keelbase-ui —— 这个宿主渲染用的视觉层

这些文件就是 KeelBase 视觉层：产品自有前端所用的那套 Element Plus 令牌（颜色、圆角、字体）。
把它们放在这里，是为了让**宿主的界面**能按我们的样子渲染，而**不改动任何一个上游文件**。

## 它们是一份生成出来的副本

令牌只在 `Web-Admin-Vue` 里写一次：

    Web-Admin-Vue/src/styles/element-theme.scss
    Web-Admin-Vue/src/styles/themes/{_blue,_teal,_graphite}.scss

要改**去那里改**，然后重新入库并校验：

    bash scripts/sync-ui-theme.sh          # 写入副本
    bash scripts/sync-ui-theme.sh --check  # 只校验；漂移即非零退出

副本必须保持逐字节一致（换行已归一），**不要就地编辑这些文件**——`--check` 存在的意义就是逮住这件事。

## 这些令牌是什么

纯 CSS 自定义属性，由 `<html data-theme>` 与 `<html class="dark">` 驱动：三套可切换主题
（深靛默认 / 青绿 / 石墨）× 亮暗两态。这里没有任何东西会编译成构建期变量，所以这一层可以**在应用
自己的样式表之后**再放进去、在运行时生效——上游的 SCSS 一行都不用改。

在本宿主里**只有默认（深靛）那套是活的**：应用没有 `data-theme` 切换器，故 `teal` 与 `graphite`
两段永远不会命中。它们仍随副本一起带过来，好让三套主题保持「一份被撰写的东西」，而不是分叉。

## 这一层怎么铺上去

**构建期注入**——视觉层只存在于产物里，从不进入源码。

    bash scripts/build-host-ui.sh              # 按需拉取、装依赖、构建、注入
    bash scripts/build-host-ui.sh --skip-build # 在已有的产物目录上注入

构建所用的检出始终不会被改动。代价是：界面自己的开发服务器（dev 模式）渲染的仍是上游的样子；
只有构建产物带着这一层。

## 与 RuoYi 宿主不同的两条事实

1. **界面不在本仓。** 上游把 `yudao-ui/yudao-ui-admin-vue3` 留成占位目录，故构建脚本把真正的树
   拉到 `target/`——那是上游 `.gitignore` 已忽略的路径，因此**没有为把它挡在索引外而改动任何受
   跟踪文件**。

2. **应用在挂载时把自己的主题色写成 `<html>` 的内联样式**（`ConfigGlobal` → `appStore.setCssVarTheme()`）。
   内联样式本身就压过普通样式表，所以「最后链一个 CSS」能带上**除主色族以外**的一切。`!important`
   能压过非重要的内联样式，故构建只给那一个族加上它——其余层保持在运行时可被覆盖，宿主的主题选择器
   对别的部分照常有效。**副作用**是：宿主自己的主色选择器不再能明显改变页面——这一层是**刻意**把主色
   钉住的。
