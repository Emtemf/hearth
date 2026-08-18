# Spec change workflow

跨 bounded context、改变领域 invariant、API 或 migration 计划的提案，在实现前创建：

```text
changes/<change-id>/
  proposal.md     问题、Context/SourceRef、决策、非目标、风险
  spec-delta.md   对 canonical owner 文档的 ADD/MODIFY/REMOVE
  tasks.md        实施切片、migration/API compatibility、可执行验收
```

流程：`/sdd-propose` → proposal review → `/sdd-spec` 修改 canonical docs →
`/sdd-implement <change-id>` 实现与 Evidence → `/sdd-verify <change-id>` 验收后移入
`changes/archive/<change-id>/`。小型错字、链接和不改变 invariant 的澄清可直接修改 owner 文档。

`changes/` 是变更记录，不替代 `docs/` 中的当前事实；冲突时以
`docs/16-spec-governance.md` 的 owner map 为准。阶段规则仅通过显式 `/sdd-*` 命令加载；
`changes/CLAUDE.md` 只定义目录语义，不重复每个阶段的规则。

文档阶段运行：

```bash
python3 scripts/validate_docs.py
git diff --check
```

验证包含本地 Markdown 相对链接、单文档 JSON 示例、canonical drift check 和 credential-like
scan。它不替代未来 runtime architecture/schema/API contract tests。
