# ReasonWeave 视觉资产来源与授权

本清单覆盖仓库中随源码分发的品牌图形、界面导航图标、产品截图和演示媒体。项目维护者已确认这些资产由项目拥有，可以与源码一起按 [Apache License 2.0](LICENSE) 分发。

产品截图和演示媒体使用远程隔离测试栈生成的完全合成数据，不包含生产事件、用户数据或第三方界面。演示流程实际执行事件创建、Bundle 导入、人工确认、真实 Qwen3 Embedding 检索、调查、图谱和审计；关键帧由项目测试自动化采集，媒体文件由 FFmpeg 7.0.2 编码。

| 路径 | 来源类别 | 用途 |
| --- | --- | --- |
| `design/assets/reasonweave-logo-horizontal.svg` | 项目品牌资产 | 设计基线中的横向标志 |
| `design/assets/reasonweave-mark.svg` | 项目品牌资产 | 设计基线中的品牌图标 |
| `frontend/public/brand/reasonweave-logo-horizontal.svg` | 项目品牌资产 | 前端横向标志 |
| `frontend/public/brand/reasonweave-mark.svg` | 项目品牌资产 | 前端品牌图标 |
| `frontend/public/icons/nav-domain-packs.svg` | 项目原创导航图标 | 领域包导航图标 |
| `frontend/public/icons/nav-events.svg` | 项目原创导航图标 | 事件导航图标 |
| `frontend/public/icons/nav-evidence.svg` | 项目原创导航图标 | 证据导航图标 |
| `frontend/public/icons/nav-knowledge.svg` | 项目原创导航图标 | 知识导航图标 |
| `frontend/public/icons/nav-overview.svg` | 项目原创导航图标 | 概览导航图标 |
| `docs/screenshots/reasonweave-investigation.webp` | 项目界面合成截图 | README 调查工作台展示 |
| `docs/screenshots/reasonweave-result.webp` | 真实测试栈合成截图 | README 调查输入与输出结果展示 |
| `docs/screenshots/reasonweave-graph.webp` | 项目界面合成截图 | README 因果图展示 |
| `docs/screenshots/reasonweave-retrieval.webp` | 项目界面合成截图 | README 检索检查器展示 |
| `docs/screenshots/reasonweave-domain-packs.webp` | 项目界面合成截图 | README 双领域包展示 |
| `docs/media/reasonweave-demo.gif` | 项目界面合成录制 | README 内嵌产品流程演示 |
| `docs/media/reasonweave-demo.mp4` | 项目界面合成录制 | 可下载的产品流程视频 |

以下 SHA-256 清单由 `scripts/verify-open-source-readiness.mjs` 校验。新增、删除或修改视觉资产时必须同步更新本文件。

```text
09b8b1cdd416fa92edb89a6de9ac5eff65755299e8b359d2fd5861485f5d10a0  design/assets/reasonweave-logo-horizontal.svg
f32f8c8a79d91317770dc45c14dd2a14fc671b465ca5c79a439fd4449334f4ae  design/assets/reasonweave-mark.svg
09b8b1cdd416fa92edb89a6de9ac5eff65755299e8b359d2fd5861485f5d10a0  frontend/public/brand/reasonweave-logo-horizontal.svg
f32f8c8a79d91317770dc45c14dd2a14fc671b465ca5c79a439fd4449334f4ae  frontend/public/brand/reasonweave-mark.svg
472d35af8ad29c707939b1e8d7593742267e18b16ee52a243264d61aa0f0eac4  frontend/public/icons/nav-domain-packs.svg
c557f15152cb99d3e44d1b2c4aac9e3a39ae78bf703cfb9561147af135fdf866  frontend/public/icons/nav-events.svg
10f5f8f2869b81aa6abe9a23e41eec82ce448e94a9413795f81b7b0be37ae197  frontend/public/icons/nav-evidence.svg
b7945421bd692f7d266763530575cf11a84ce0204c9eee2d2cad9f2cd1bfc3a6  frontend/public/icons/nav-knowledge.svg
1c99e57fa52427906fc2efa52a9727b5226b8303419bdea0d0315527134cf5c8  frontend/public/icons/nav-overview.svg
5a43363163b09b3418e9948aba3f3c8ea0b783f2f7f137fcc3c11225c776ffce  docs/screenshots/reasonweave-investigation.webp
c7774d74c612b329bd1f354f3149cb8fa3bd07636f5d9c76abe3805ce97d0bde  docs/screenshots/reasonweave-result.webp
55fe56d850bccef737a3119268d7cdf57b9f56d0df613700e1dd445813adbc47  docs/screenshots/reasonweave-graph.webp
f37530db5a26722787f782791831fef78ed70af5250f6ccd3bbeef996174b9f4  docs/screenshots/reasonweave-retrieval.webp
52d7a41d8fc5b8c244ebf376636181e6d5991979ae9211a061cc6992ec39ac1f  docs/screenshots/reasonweave-domain-packs.webp
a605b102d1662eb4c7ce3292abccb2a678ce74184a44406da401073a03b19676  docs/media/reasonweave-demo.gif
c8198b85a77861199dc9a5ccc7d98a1430358ea931455e863499ace03518b015  docs/media/reasonweave-demo.mp4
```
