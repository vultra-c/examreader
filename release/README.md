# 安装包

本目录只存放手环端考点阅读器的当前可安装构建产物。

| 文件 | 平台 | 包名 | 版本 | 说明 |
|------|------|------|------|------|
| [`com.whyy.snapnotes.release.V26.8.30.BAND.rpk`](com.whyy.snapnotes.release.V26.8.30.BAND.rpk) | 小米 Vela | `com.whyy.snapnotes` | `V26.8.30.BAND` | 考点阅读器（闪念小抄） |

配套安装包已迁至独立仓库（各仓库 Action 每次提交自动构建）：

- Android 手机端：[vultra-c/Snapnotes-android](https://github.com/vultra-c/Snapnotes-android)（Actions artifact）
- 化学工具箱：[vultra-c/Chemical-calculator](https://github.com/vultra-c/Chemical-calculator)（Actions artifact）

## 安装说明

- `.rpk` 为已签名的 Vela 安装包，请使用 Vela 开发工具或设备调试工具安装。

## 重新构建

```bash
cd ../考点阅读器 && npm install && npm run release
cp dist/*.rpk ../release/
```
