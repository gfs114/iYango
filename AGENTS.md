# Core Product & Engineering Rules

## 1. 界面与文案极简准则 (UI & Copy Minimalism)
- **砍掉冗余文字**：移除标语（slogans）、提示文字（helper/hint text）和次要标签（secondary labels）。
- **布局优先于解释**：如果一个页面必须依赖辅助文字才能看懂，必须通过重构布局与视觉层级来解决，而不是保留或增加说明文字。
- **禁止圆点分隔**：两个元素或信息项之间不要加点（如 `·` 或 `•`），直接换行。

## 2. 决策与变更准则 (Decision & Change Invariants)
- **UX / DX / AX 同步提升**：每一个设计与代码决定都必须同时提升用户体验（UX）、开发者体验（DX）和智能体体验（AX）。
- **零破坏原则**：任何改动都不能弄坏现有功能、状态或交互闭环。

## 3. 镜像同步准则 (Release Mirror Sync)
- **仅同步有用生产部分**：在 `G:\code\Android\iYango` 完成功能或修复改动并验证通过后，按 `.agents/skills/sync-release-mirror/SKILL.md` 将有效生产代码、资源与构建配置同步到 `G:\code\Android\test\iYango`。
- **严禁同步测试与文档**：绝不向目标目录同步 `*/src/test`、`*/src/androidTest`、`docs/`、`diagnostics/`、`.agents/`、`.publish-iYango/`、`.mimosa/`、`build/`、`.gradle/`、`.idea/`、`local.properties` 等测试、文档与本地开发产物。
- **保护目标库专有文件**：同步时严禁覆盖或删除目标目录中的 `.git/`、`release.keystore`、`.github/`、`README.md`、`LICENSE`。

## 4. 版本递增准则 (Version Bump Policy)
- **新功能自动升 0.1**：每轮完成并验证通过新功能交付（单次包含多个新功能时仍按整轮统一加 `0.1`），在 `app/build.gradle.kts` 中将 `versionName` 按两段式 `X.Y` 自动加 `0.1`（如 `1.0` → `1.1` → `1.2`，满 `X.9` 按十进制进位为 `(X+1).0`），同时将 `versionCode` 加 `1`，并保持 `HttpAppUpdateRepository.kt` 中的兜底版本号一致。
- **仅新功能触发递增**：纯 Bug 修复、重构、测试或文档调整不自动递增版本号（除非用户显式要求）。
- **随镜像同步**：版本号变更后按第 3 节规则一并同步至 `G:\code\Android\test\iYango`。

## 5. GitHub 发布触发准则 (Tag-Only Release Trigger)
- **仅 `v*` 标签触发 GitHub 更新**：推送常规提交到 `main` 等分支时，不触发 GitHub Actions 编译与发布；只有明确发布正式版本并推送 `v*` 格式标签（如 `git tag -a v1.1 -m "Release v1.1" && git push origin v1.1`）时，才触发 GitHub Actions 编译 Release APK 并发布 GitHub Release。
- **工作流触发约束**：`G:\code\Android\test\iYango\.github\workflows\release.yml` 仅监听 `push.tags: ["v*"]`（及手动 `workflow_dispatch`），禁止添加 `push.branches`。
- **同步推送默认不打标签**：向发布镜像库执行 `git push` 时默认仅推送分支提交，除非用户显式要求发布版本或推送 `v*` 标签。
