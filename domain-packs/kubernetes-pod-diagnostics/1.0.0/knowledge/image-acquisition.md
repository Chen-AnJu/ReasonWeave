# 容器镜像获取失败

来源：[Kubernetes Images](https://kubernetes.io/docs/concepts/containers/images/)，基于文档仓库 `dev-1.37` 修订版。本文件为经修改的中文摘要，按 CC BY 4.0 使用。

## 可观察状态

当 Kubelet 无法拉取镜像时，容器等待原因可能先出现 `ErrImagePull`，随后进入 `ImagePullBackOff`。退避状态表示系统正在降低重试频率；它并不区分镜像不存在、名称错误、仓库认证失败、网络不可达或拉取策略不合适。

`InvalidImageName` 对无效镜像引用具有较强指向性。对其他拉取错误，应检查容器等待消息、镜像引用、`imagePullPolicy`、仓库可达性和 `imagePullSecrets` 的引用是否存在，但不得采集或展示 Secret 内容。

## 证据边界

等待原因来自 Pod status，可靠度高于对错误消息的自由文本猜测。即使镜像已成功拉取，也不能据此排除同一 Pod 中其他容器的镜像问题，必须按容器检查。
