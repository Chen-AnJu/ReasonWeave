# Pod 生命周期与容器崩溃

来源：[Kubernetes Pod Lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)，基于文档仓库 `dev-1.37` 修订版。本文件为经修改的中文摘要，按 CC BY 4.0 使用。

## 等待与终止状态

诊断 Pod 时应优先查看 `status.containerStatuses` 和 `status.initContainerStatuses`。等待状态的 `reason` 描述容器尚未运行的直接状态；终止状态同时提供退出码和终止原因。非零退出码只能证明进程异常结束，不能单独证明具体业务根因。

## CrashLoopBackOff

`CrashLoopBackOff` 表示容器启动后反复失败，Kubelet 正在延长重启间隔。它是一种可观察状态，不是根因。应结合上一次终止原因、退出码、重启次数和前一实例日志判断。`OOMKilled`、配置错误、启动命令失败和存活探针失败都可能导致反复重启。

## 证据边界

Pod phase 过于粗粒度，不能替代每个容器的状态。调查快照应保存实际容器状态字段及采集时间；后续状态变化不能改写旧调查。
