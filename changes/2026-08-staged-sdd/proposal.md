# Staged SDD workflow

## 状态

- 状态：已实现，文档与命令验证通过
- 日期：2026-08-17
- 范围：Claude Code 项目工作流与规格文档治理

## 问题与 Context

Hearth 已经有 canonical owner 文档和 `changes/<change-id>/` 变更记录格式，但没有显式的 proposal、spec、implement、verify 阶段入口。把阶段规则直接追加到根 `CLAUDE.md` 或复制到每个 change 目录，会让无关阶段互相污染，并增加 canonical source 漂移风险。

SourceRef：`docs/16-spec-governance.md`、`changes/README.md`、根 `CLAUDE.md` 以及 Claude Code 项目级命令约定。

## 决策

1. 根 `CLAUDE.md` 只保留 SDD 流程入口和 canonical source 路由。
2. `changes/CLAUDE.md` 只定义该子树的语义，不创建每个阶段的嵌套规则文件。
3. 使用显式 `/sdd-propose`、`/sdd-spec`、`/sdd-implement`、`/sdd-verify` 命令提供阶段控制。
4. 使用仓库内 `scripts/validate_docs.py` 执行文档链接、JSON 示例、漂移术语和 credential-like 检查。
5. 阶段命令和验证脚本不成为 Hearth 领域 invariant 的新 owner；当前事实仍由 `docs/16-spec-governance.md` 及其 owner map 决定。

## 非目标

- 不引入运行时 SDD aggregate、数据库表、REST API 或 Schedule 语义。
- 不把提醒式 hooks 当作强制治理约束。
- 不新增 `specs/` 平行源目录，也不迁移现有 `docs/` owner 文档。
