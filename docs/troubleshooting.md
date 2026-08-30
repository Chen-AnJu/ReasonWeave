# 故障排查

以下命令默认在仓库根目录执行。ReasonWeave 预览版只应绑定回环地址；不要为了排障把未认证的 API 暴露到公网。

## 模型下载很慢或看不到进度

首次启动会把 `qwen3-embedding:0.6b` 下载到固定命名卷 `reasonweave-ollama-model-cache`。查看一次性模型任务日志：

```console
docker compose logs -f ollama-model
```

后续 `docker compose down` 和重启会复用该卷。只有显式删除卷才会重新下载模型。

- 首次下载日志包含 `Ollama model cache miss; pulling once` 和 639 MB 进度。
- 缓存命中日志包含 `Ollama model cache hit`，不会再次拉取模型。

参考验收链路在约 3.6–4.5 MB/s 时完成空缓存下载用了 164.74 秒，落盘约 609.6 MiB；你的耗时主要取决于到 Ollama 模型仓库的网络。不要仅因进度输出短暂停顿就删除模型卷。

## 内存不足或容器被终止

完整栈包含 PostgreSQL、Ollama、后端和前端。建议至少 4 GiB 可用内存，运行真实向量检索时建议 6 GiB 以上。先查看：

```console
docker compose ps
docker compose logs --tail=200 ollama backend
```

不要在内存不足时静默切换到 Mock Provider；正式领域包会把索引标为未就绪并阻止调查。

## 端口 8080 已占用

通过环境变量改用另一个回环端口：

```console
RW_HTTP_PORT=8088 docker compose up -d
```

PowerShell：

```powershell
$env:RW_HTTP_PORT = '8088'
docker compose up -d
```

随后访问 `http://127.0.0.1:8088`。不要修改 Compose 让后端、数据库或 Ollama 暴露宿主端口。

## 领域包显示“索引未就绪”

依次检查模型任务、Ollama 和后端：

```console
docker compose ps
docker compose logs ollama-model
docker compose logs --tail=200 backend
curl --fail-with-body http://127.0.0.1:8080/api/v1/domain-packs
```

常见原因是模型仍在下载、模型摘要不匹配、Embedding 维度不是 1024，或向量索引尚未完成。ReasonWeave 不会把 FTS-only 结果伪装成生产混合检索。

## 领域包校验和漂移

Windows 检出必须遵循仓库 `.gitattributes` 的 LF 规则。不要用编辑器批量改写 YAML、Markdown 或 `checksums.sha256` 的换行。验证：

```console
pnpm rwpack validate domain-packs/kubernetes-pod-diagnostics/1.0.0
pnpm rwpack validate domain-packs/cold-holding-excursion-diagnostics/1.0.0
```

如果你有意修改了包内容，必须升级版本并重新执行完整打包、许可证和 Golden Fixture 流程；不要只覆盖旧校验和。

## 清理本地数据卷

`docker compose down` 只停止服务，不删除数据。以下命令会永久删除当前 Compose 项目的数据库和 Blob 数据，应先自行备份：

```console
docker compose down -v
```

模型缓存使用固定卷 `reasonweave-ollama-model-cache`，通常无需删除。删除它会导致下一次启动重新下载模型。

## 获取可报告的信息

Bug 报告请提供镜像标签/摘要、最小复现、`meta.request_id` 和已脱敏日志。不要提交密码、生产证据、kubeconfig、完整遥测或私有网络信息。更多边界见 [SUPPORT.md](../SUPPORT.md) 与 [SECURITY.md](../SECURITY.md)。
