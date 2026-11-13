# 证据

每个目录是一款手机在一个网关版本上的测试结果：

```
evidence/<厂商>-<型号>-android<版本>/<网关版本>/
  device.txt     手机的型号、系统、芯片，和音频策略里与通话有关的几行
  state.txt      网关的版本、权限、默认应用、服务状态
  bridge.log     音频服务的日志：每通电话两个方向的帧数、非静音帧数、峰值
  gateway.log    系统日志里网关自己的行（有的系统会过滤掉，可能是空的）
  report.md      人填的：做了哪些项，听到了什么，有什么要注意的
```

前四个文件由 `tools/evidence.py collect` 从手机上读出来，写入前遮掉了电话号码、网络地址、设备编号。不要手工改它们，也不要放别的文件进来（截图、录音、原始日志都不要）。

`tools/evidence.py check <目录>` 会说这份证据达到了 [docs/TESTING.md](../docs/TESTING.md) 里的哪一级。

目录名用小写和连字符，型号用 `ro.product.model` 的值，例如 `xiaomi-21091116ac-android13/0.43.0`。
