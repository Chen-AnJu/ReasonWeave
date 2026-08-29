# Pod 调度、配置与挂载诊断

来源：[Debug Running Pods](https://kubernetes.io/docs/tasks/debug/debug-application/debug-running-pod/)，基于文档仓库 `dev-1.37` 修订版。本文件为经修改的中文摘要，按 CC BY 4.0 使用。

## 无法调度

`PodScheduled=False` 且 reason 为 `Unschedulable`，是调度约束假设的直接证据。原因可能涉及资源不足、节点选择器或亲和性不匹配、污点缺少容忍、拓扑约束，或 PVC 尚未满足。`SchedulingGated` 则表示 Pod 明确被调度门控阻塞。

## 配置与挂载

`CreateContainerConfigError` 指向容器启动前的配置解析问题；相关对象名称、引用存在性和命名空间应继续核对。`FailedMount` Event 可提示卷、Secret 或 ConfigMap 挂载没有完成，但 Event 是补充信息，不能脱离 Pod status 和对象现状单独下结论。

## 安全边界

诊断只需记录引用是否存在及状态原因。不要读取或上传 Secret 值、环境变量值、ServiceAccount Token 或私有仓库凭证。
