# Spec delta

本变更只增加工作流入口与文档验证，不改变 Hearth runtime 的领域 invariant。

## ADD / MODIFY

- `CLAUDE.md`：增加 SDD 阶段命令与 canonical source 路由。
- `changes/README.md`：记录显式 SDD 阶段和文档验证入口。
- `changes/CLAUDE.md`：限定 `changes/` 子树语义，禁止阶段规则复制到每个 change 目录。
- `.claude/commands/sdd-propose.md`：定义 proposal 阶段入口。
- `.claude/commands/sdd-spec.md`：定义 canonical spec delta 与 tasks 阶段入口。
- `.claude/commands/sdd-implement.md`：定义小切片、测试优先和 Evidence 记录入口。
- `.claude/commands/sdd-verify.md`：定义文档和实现验证入口。
- `scripts/validate_docs.py`：实现设计阶段的文档 hygiene 检查。
- `docs/16-spec-governance.md`：记录统一验证命令。

## REMOVE / REJECT

- 不新增 `.claude/rules/sdd*.md` 作为全局详细阶段规则。
- 不新增每个 change 目录的阶段性 `CLAUDE.md`。
- 不把 `.codex/hooks.json` 或提醒式 hook 纳入治理契约。

## Compatibility

本变更不产生数据库 migration、REST/API wire 变化或运行时兼容性要求。未来 runtime 实现仍必须补充 architecture/schema/API contract tests。
