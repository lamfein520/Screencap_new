# Android Screen Recorder

一个简单但功能完整的 Android 屏幕录制应用。使用 MediaCodec 进行视频编码，支持高质量的屏幕录制。

## 功能特点

- 📱 高质量屏幕录制
- 🎥 使用 MediaCodec 硬件编码，性能更好
- 💾 自动保存为 MP4 格式
- 🔒 完整的权限管理
- 📊 详细的日志记录，方便调试
- 🔄 前台服务确保录制稳定运行

## 运行要求

- Android SDK 版本：34 或更高
- 设备系统：Android 10.0 或更高
- 存储空间：建议至少 100MB 可用空间

## 如何使用

1. 安装应用后，首次运行会请求以下权限：
   - 前台服务权限
   - 屏幕录制权限
   - 通知权限
   - 麦克风权限（如果需要录制声音）

2. 点击"开始录制"按钮：
   - 首次点击会请求必要权限
   - 同意权限后会开始录制
   - 录制过程中会显示通知

3. 再次点击按钮停止录制：
   - 录制文件会自动保存到设备的 Movies 目录
   - 文件名格式：yyyy-MM-dd_HH-mm-ss.mp4

## 技术实现

### 核心组件

1. **MainActivity**
   - 处理用户界面交互
   - 管理权限请求
   - 控制录制服务

2. **RecordService**
   - 前台服务，确保录制稳定运行
   - 使用 MediaCodec 进行视频编码
   - 使用 MediaMuxer 生成 MP4 文件

### 关键技术

- **MediaProjection**: 用于捕获屏幕内容
- **MediaCodec**: 硬件加速的视频编码器
- **VirtualDisplay**: 虚拟显示设备，用于承载屏幕内容
- **MediaMuxer**: 视频封装器，生成 MP4 文件

## 项目结构

```
app/src/main/
├── java/com/example/screepcap/
│   ├── service/
│   │   └── RecordService.java    # 录制服务
│   └── view/
│       └── MainActivity.java      # 主界面
└── res/
    └── layout/
        └── activity_main.xml      # 界面布局
```

## 如何编译

1. 克隆项目：
   ```bash
   git clone git@github.com:lamfein520/Screencap_new.git
   ```

2. 使用 Android Studio 打开项目

3. 等待 Gradle 同步完成

4. 点击运行按钮或使用命令行：
   ```bash
   ./gradlew assembleDebug
   ```

## 常见问题

1. **录制没有声音**
   - 确保已授予麦克风权限
   - 检查系统音量设置

2. **应用闪退**
   - 检查是否授予了所有必要权限
   - 查看日志以获取详细错误信息

3. **找不到录制文件**
   - 录制文件保存在设备的 Movies 目录下
   - 文件名格式为：yyyy-MM-dd_HH-mm-ss.mp4

## 贡献指南

欢迎提交 Pull Request 或创建 Issue！

1. Fork 本项目
2. 创建您的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交您的修改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启一个 Pull Request

## 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 联系方式

如果您有任何问题或建议，欢迎联系我：

- GitHub：lamfein520
- Email：lamfein520@gmail.com 

## 致谢

感谢所有贡献者的付出！

---

如果这个项目对您有帮助，请给个 ⭐️ 支持一下！
