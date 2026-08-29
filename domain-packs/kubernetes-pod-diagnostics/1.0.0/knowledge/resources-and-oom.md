# 资源约束、驱逐与 OOM

来源：[Resource Management for Pods and Containers](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/)，基于文档仓库 `dev-1.37` 修订版。本文件为经修改的中文摘要，按 CC BY 4.0 使用。

## 请求与限制

调度器主要使用资源 request 判断节点是否能够承载 Pod。节点当前看似空闲并不意味着请求一定可被调度；应比较可分配资源、已有请求、调度约束和 Pod 自身请求。

## 内存终止

容器终止原因 `OOMKilled` 是内存压力的重要直接证据。需要进一步比较工作集、内存 limit、应用峰值和节点压力。单次 OOM 不能证明内存泄漏，也不能自动给出新的 limit。

## 驱逐

Pod `status.reason=Evicted` 表示 Kubelet 在节点压力等条件下终止了 Pod。驱逐可能与内存、磁盘或 inode 压力相关，需要读取具体状态和节点条件后再区分。
