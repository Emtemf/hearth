# Tasks and acceptance

## 文档任务

- [x] 增加显式 SDD 阶段命令，避免把阶段语义加载到所有任务。
- [x] 添加 `changes/CLAUDE.md`，仅限定变更记录子树。
- [x] 保持 `docs/16-spec-governance.md` 为 canonical owner map。
- [x] 添加可执行的文档验证器。
- [x] 为验证器增加 focused unit tests。

## 文档验收

- [x] `python3 -m unittest scripts/test_validate_docs.py` 通过。
- [x] `python3 scripts/validate_docs.py` 通过。
- [x] `git diff --check` 无输出。
- [x] 自检：命令和路由文件未复制完整 canonical specification。
- [x] credential-like scan 未发现真实密钥、token 或私钥。

## 实现前验收

- [ ] 有 runtime 代码后，将 canonical invariant 转换为 architecture/schema/API contract tests。
- [ ] 有 Maven/前端项目后，将 SDD implement 阶段接入对应 focused test、coverage 和 review gates。
