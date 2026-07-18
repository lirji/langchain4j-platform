<script setup lang="ts">
/**
 * 自助注册页 /register（成功即登录）。
 *
 * 运行时门禁：仅当 auth.publicConfig.registrationEnabled===true 才可用（守卫已挡；此处再兜底提示）。
 * 字段仅 username / password / 确认密码——**不暴露 tenant / role 选择**（由后端规则引擎按用户名域名推导）。
 * 密码策略（min/max）来自 publicConfig，实时校验、提交前暴露错误。复用 LoginView 的极光玻璃视觉体系。
 */
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { ApiError, humanizeError } from '../../api/errors'

const auth = useAuthStore()
const router = useRouter()

const username = ref('')
const password = ref('')
const confirm = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const errorMsg = ref('')

/** 注册是否开放（未探测 / 关闭均视为不可用，fail-closed）。 */
const registrationEnabled = computed(() => auth.publicConfig?.registrationEnabled === true)
const passwordMin = computed(() => auth.publicConfig?.passwordMinLength ?? 8)
const passwordMax = computed(() => auth.publicConfig?.passwordMaxLength ?? 64)
const passwordRule = computed(() => `密码需 ${passwordMin.value}–${passwordMax.value} 位`)

const FEATURES = [
  { icon: '💬', title: '对话 · 检索增强', desc: '多轮记忆 · 混合 RAG · GraphRAG' },
  { icon: '🤖', title: '智能体编排', desc: 'ReAct · 多 Agent DAG · 人在环' },
  { icon: '🎙️', title: '多模态 · 语音', desc: '视觉描述 · 语音闭环 ASR→TTS' },
]

/** 优先展示后端人话 message（用户名已占用 / 注册未开放 / 频率限制），否则回落通用翻译。 */
function registerErrorText(e: unknown): string {
  if (e instanceof ApiError && e.body && typeof e.body === 'object') {
    const msg = (e.body as Record<string, unknown>).message
    if (typeof msg === 'string' && msg.trim()) return msg
  }
  return humanizeError(e)
}

async function submit(): Promise<void> {
  errorMsg.value = ''
  const u = username.value.trim()
  if (!u) {
    errorMsg.value = '请输入用户名。'
    return
  }
  if (password.value.length < passwordMin.value || password.value.length > passwordMax.value) {
    errorMsg.value = `${passwordRule.value}。`
    return
  }
  if (password.value !== confirm.value) {
    errorMsg.value = '两次输入的密码不一致。'
    return
  }
  submitting.value = true
  try {
    await auth.register(u, password.value)
    await router.replace('/')
  } catch (e) {
    errorMsg.value = registerErrorText(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="lp-root">
    <div class="lp-aurora" aria-hidden="true">
      <span class="lp-blob lp-blob-1" />
      <span class="lp-blob lp-blob-2" />
      <span class="lp-blob lp-blob-3" />
      <span class="lp-blob lp-blob-4" />
    </div>

    <div class="lp-stage">
      <div class="lp-card">
        <aside class="lp-brand">
          <div class="lp-brand-logo">◆ 能力控制台</div>
          <div class="lp-brand-tag">对话 · 检索 · 智能体 · 多模态 一体化试用台</div>
          <div class="lp-feats">
            <div v-for="f in FEATURES" :key="f.title" class="lp-feat">
              <span class="lp-feat-ico">{{ f.icon }}</span>
              <div>
                <div class="lp-feat-title">{{ f.title }}</div>
                <div class="lp-feat-desc">{{ f.desc }}</div>
              </div>
            </div>
          </div>
          <div class="lp-brand-foot">微服务 AI 平台 · 两层网关 · 内部试用</div>
        </aside>

        <section class="lp-form">
          <div class="lp-compact-head">
            <div class="lp-compact-logo">◆ 能力控制台</div>
            <div class="lp-compact-tag">一体化能力试用台</div>
          </div>

          <h1 class="lp-title">创建账号</h1>
          <p class="lp-subtitle">内部试用台 · 注册后自动登录</p>

          <!-- 注册未开放兜底（守卫外的双保险） -->
          <div v-if="!registrationEnabled" class="lp-closed">
            <p class="lp-closed-title">注册当前未开放</p>
            <p class="lp-closed-desc">平台未启用自助注册，请联系管理员开通账号。</p>
            <RouterLink to="/login" class="lp-back-link">返回登录</RouterLink>
          </div>

          <template v-else>
            <div v-if="errorMsg" class="lp-error" role="alert">
              <span aria-hidden="true">⚠️</span><span>{{ errorMsg }}</span>
            </div>

            <form class="lp-fields" @submit.prevent="submit">
              <div class="lp-field">
                <label class="lp-label" for="reg-username">用户名</label>
                <div class="lp-input-wrap">
                  <span class="lp-input-ico" aria-hidden="true">👤</span>
                  <input
                    id="reg-username"
                    v-model="username"
                    class="lp-input"
                    type="text"
                    autocomplete="username"
                    spellcheck="false"
                    :disabled="submitting"
                    placeholder="设置用户名"
                  />
                </div>
              </div>

              <div class="lp-field">
                <label class="lp-label" for="reg-password">密码</label>
                <div class="lp-input-wrap">
                  <span class="lp-input-ico" aria-hidden="true">🔒</span>
                  <input
                    id="reg-password"
                    v-model="password"
                    class="lp-input lp-input--pw"
                    :type="showPassword ? 'text' : 'password'"
                    autocomplete="new-password"
                    :disabled="submitting"
                    placeholder="设置密码"
                  />
                  <button
                    type="button"
                    class="login__pw-toggle"
                    :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                    @click="showPassword = !showPassword"
                  >
                    {{ showPassword ? '🙈' : '👁️' }}
                  </button>
                </div>
              </div>

              <div class="lp-field">
                <label class="lp-label" for="reg-confirm">确认密码</label>
                <div class="lp-input-wrap">
                  <span class="lp-input-ico" aria-hidden="true">🔒</span>
                  <input
                    id="reg-confirm"
                    v-model="confirm"
                    class="lp-input lp-input--pw"
                    :type="showPassword ? 'text' : 'password'"
                    autocomplete="new-password"
                    :disabled="submitting"
                    placeholder="再次输入密码"
                  />
                </div>
              </div>

              <p class="lp-rule" role="note">{{ passwordRule }}（来自服务端策略）</p>

              <button type="submit" class="lp-btn" :disabled="submitting">
                <span v-if="submitting" class="lp-spin" aria-hidden="true" />
                {{ submitting ? '创建中…' : '创 建 账 号' }}
              </button>
            </form>

            <p class="lp-foot-note">
              已有账号？<RouterLink to="/login" class="lp-back-link">返回登录</RouterLink>
            </p>
          </template>
        </section>
      </div>
      <div class="lp-foot">内部试用台 · 数据仅供演示</div>
    </div>
  </div>
</template>

<style scoped>
/* 自成一体的亮色配色（复用 LoginView 视觉体系，不跟随应用暗色主题）。 */
.lp-root {
  --lp-primary: #2d6cdf;
  --lp-ink: #1e2a44;
  --lp-muted: #5b6b8c;
  --lp-subtle: #8b98b4;
  --lp-line: rgba(45, 108, 223, 0.14);
  position: relative;
  flex: 1;
  min-height: 100vh;
  min-height: 100dvh; /* 移动浏览器地址栏收放不跳动 */
  width: 100%;
  overflow-y: auto;
  display: flex;
  background: linear-gradient(160deg, #eaf1ff 0%, #f2f0ff 46%, #e9fbff 100%);
  color: var(--lp-ink);
  font-feature-settings: 'tnum';
}
.lp-aurora {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
}
.lp-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  will-change: transform;
}
.lp-blob-1 {
  width: 540px; height: 540px; top: -12%; left: -8%;
  background: radial-gradient(circle at center, rgba(45, 108, 223, 0.8), rgba(45, 108, 223, 0) 68%);
  animation: lp-float-a 24s ease-in-out infinite alternate;
}
.lp-blob-2 {
  width: 480px; height: 480px; top: 4%; right: -8%;
  background: radial-gradient(circle at center, rgba(34, 211, 238, 0.7), rgba(34, 211, 238, 0) 68%);
  animation: lp-float-b 29s ease-in-out infinite alternate;
}
.lp-blob-3 {
  width: 600px; height: 600px; bottom: -20%; left: 16%;
  background: radial-gradient(circle at center, rgba(99, 102, 241, 0.68), rgba(99, 102, 241, 0) 68%);
  animation: lp-float-c 33s ease-in-out infinite alternate;
}
.lp-blob-4 {
  width: 440px; height: 440px; bottom: -8%; right: 8%;
  background: radial-gradient(circle at center, rgba(139, 92, 246, 0.6), rgba(139, 92, 246, 0) 68%);
  animation: lp-float-a 27s ease-in-out infinite alternate-reverse;
}
@keyframes lp-float-a {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(7%, -5%) scale(1.12); }
  100% { transform: translate(-5%, 6%) scale(0.96); }
}
@keyframes lp-float-b {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(-8%, 6%) scale(1.1); }
  100% { transform: translate(5%, -6%) scale(0.94); }
}
@keyframes lp-float-c {
  0% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(6%, 7%) scale(1.08); }
  100% { transform: translate(-7%, -5%) scale(1); }
}
.lp-stage {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 32px 20px;
}
.lp-card {
  display: flex;
  width: 100%;
  max-width: 920px;
  border-radius: 22px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(22px) saturate(165%);
  -webkit-backdrop-filter: blur(22px) saturate(165%);
  border: 1px solid rgba(255, 255, 255, 0.65);
  box-shadow:
    0 26px 70px rgba(26, 54, 110, 0.22),
    0 2px 8px rgba(26, 54, 110, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.75);
  animation: lp-rise 0.6s cubic-bezier(0.22, 1, 0.36, 1) both;
}
@keyframes lp-rise {
  from { opacity: 0; transform: translateY(16px) scale(0.985); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
.lp-brand {
  position: relative;
  flex: 0 0 44%;
  padding: 52px 44px;
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: linear-gradient(150deg, rgba(45, 108, 223, 0.95) 0%, rgba(79, 70, 229, 0.92) 52%, rgba(6, 182, 212, 0.88) 100%);
  overflow: hidden;
}
.lp-brand::before {
  content: '';
  position: absolute;
  top: -30%;
  left: -20%;
  width: 70%;
  height: 70%;
  background: radial-gradient(circle at center, rgba(255, 255, 255, 0.35), rgba(255, 255, 255, 0) 70%);
  pointer-events: none;
}
.lp-brand-logo { font-size: 30px; font-weight: 800; letter-spacing: 0.5px; }
.lp-brand-tag { font-size: 14px; opacity: 0.92; margin-top: 12px; line-height: 1.6; }
.lp-feats { margin-top: 38px; display: flex; flex-direction: column; gap: 20px; }
.lp-feat { display: flex; gap: 14px; align-items: flex-start; }
.lp-feat-ico {
  width: 40px; height: 40px; border-radius: 11px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.28);
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 18px; flex: 0 0 auto;
}
.lp-feat-title { font-weight: 600; font-size: 14.5px; }
.lp-feat-desc { opacity: 0.84; font-size: 12.5px; margin-top: 2px; }
.lp-brand-foot { margin-top: 42px; font-size: 12px; opacity: 0.72; }
.lp-form {
  flex: 1;
  min-width: 0;
  padding: 44px 44px 34px;
  display: flex;
  flex-direction: column;
}
.lp-compact-head { display: none; text-align: center; margin-bottom: 22px; }
.lp-compact-logo {
  font-size: 24px; font-weight: 800;
  background: linear-gradient(120deg, #2d6cdf, #6366f1 55%, #06b6d4);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.lp-compact-tag { font-size: 13px; color: var(--lp-muted); margin-top: 4px; }
.lp-title { font-size: 22px; font-weight: 700; color: var(--lp-ink); }
.lp-subtitle { font-size: 13px; color: var(--lp-muted); margin-top: 4px; }
.lp-error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  padding: 10px 12px;
  font-size: 13px;
  color: #b42318;
  background: #fef3f2;
  border: 1px solid #fecdca;
  border-radius: 11px;
}
.lp-closed {
  margin-top: 22px;
  padding: 18px;
  text-align: center;
  border: 1px dashed rgba(45, 108, 223, 0.28);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.5);
}
.lp-closed-title { font-size: 15px; font-weight: 700; color: var(--lp-ink); }
.lp-closed-desc { font-size: 13px; color: var(--lp-muted); margin-top: 6px; }
.lp-fields { margin-top: 20px; display: flex; flex-direction: column; gap: 15px; }
.lp-field { display: flex; flex-direction: column; gap: 6px; }
.lp-label { font-size: 12.5px; font-weight: 600; color: var(--lp-muted); }
.lp-input-wrap { position: relative; display: flex; align-items: center; }
.lp-input-ico { position: absolute; left: 12px; font-size: 14px; opacity: 0.6; pointer-events: none; }
.lp-input {
  width: 100%;
  height: 46px;
  padding: 0 14px 0 38px;
  font-size: 14px;
  color: var(--lp-ink);
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid rgba(45, 108, 223, 0.16);
  border-radius: 11px;
  transition: border-color 0.2s ease, background 0.2s ease, box-shadow 0.2s ease;
}
.lp-input::placeholder { color: var(--lp-subtle); }
.lp-input--pw { padding-right: 44px; }
.lp-input:hover { border-color: rgba(45, 108, 223, 0.45); background: rgba(255, 255, 255, 0.85); }
.lp-input:focus {
  outline: none;
  border-color: var(--lp-primary);
  background: #fff;
  box-shadow: 0 0 0 3px rgba(45, 108, 223, 0.16);
}
.login__pw-toggle {
  position: absolute;
  right: 8px;
  padding: 4px 8px;
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  opacity: 0.75;
}
.login__pw-toggle:hover { opacity: 1; }
.lp-rule { font-size: 12px; color: var(--lp-muted); margin: -2px 0 0; }
.lp-btn {
  height: 46px;
  margin-top: 4px;
  border: none;
  border-radius: 11px;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background-image: linear-gradient(120deg, #2d6cdf 0%, #4f6ff0 46%, #21a9e0 100%);
  background-size: 170% 170%;
  background-position: 0% 50%;
  box-shadow: 0 10px 24px rgba(45, 108, 223, 0.32);
  transition: background-position 0.5s ease, box-shadow 0.25s ease, transform 0.15s ease;
}
.lp-btn:not(:disabled):hover {
  background-position: 100% 50%;
  box-shadow: 0 14px 32px rgba(45, 108, 223, 0.44);
  transform: translateY(-1px);
}
.lp-btn:not(:disabled):active { transform: translateY(0); }
.lp-btn:disabled { cursor: not-allowed; opacity: 0.75; }
.lp-spin {
  width: 15px; height: 15px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  animation: lp-rotate 0.7s linear infinite;
}
@keyframes lp-rotate { to { transform: rotate(360deg); } }
.lp-foot-note { font-size: 12px; color: var(--lp-subtle); margin-top: 14px; }
.lp-back-link { color: var(--lp-primary); font-weight: 600; text-decoration: none; }
.lp-back-link:hover { text-decoration: underline; }
.lp-foot { font-size: 12px; color: rgba(30, 50, 90, 0.5); }

/* canonical 1023，与主控制台抽屉断点对齐 */
@media (max-width: 1023px) {
  .lp-brand { display: none; }
  .lp-card { max-width: 440px; }
  .lp-compact-head { display: block; }
}
@media (max-width: 640px) {
  .lp-form { padding: 30px 22px 26px; }
}
/* 触屏 16px 防 iOS 聚焦缩放 */
@media (pointer: coarse) {
  .lp-input { font-size: 16px; }
}
@media (prefers-reduced-motion: reduce) {
  .lp-blob, .lp-card, .lp-spin { animation: none !important; }
  .lp-btn, .lp-input { transition: none !important; }
}
</style>
