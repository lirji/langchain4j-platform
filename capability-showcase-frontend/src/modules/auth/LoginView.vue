<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { useCatalogStore } from '../../stores/catalog'
import { ApiError, humanizeError } from '../../api/errors'
import { sanitizeRedirect } from '../../router'
import { AUTH_MODE, DEMO_LOGIN_ENABLED, OIDC_ENABLED } from '../../config'

const auth = useAuthStore()
const catalog = useCatalogStore()
const router = useRouter()
const route = useRoute()

const tenant = ref('') // 方案C：登录前选/输租户(=Casdoor org)，用 shared app 的 <base>-org-<tenant> 客户端
const username = ref('')
const password = ref('')
const passwordEl = ref<HTMLInputElement | null>(null)
const showPassword = ref(false)
const submitting = ref(false)
const pending = ref<string | null>(null) // 正在一键登录的演示账号
const errorMsg = ref('')
const casdoorBusy = ref(false) // 正在跳转 Casdoor

const busy = computed(() => submitting.value || pending.value !== null)

/**
 * 是否展示账号密码表单：仅 apikey 模式（legacy 会话驱动）显示。
 * oidc **与 dual** 均隐藏——dual 下 store 的会话生命周期（bootstrap/refresh/logout）已由 OIDC 驱动接管，
 * 账密登录建立的 legacy 会话无法被 OIDC 续期，展示它会造成"登进去却续不上"的割裂（#1）。dual 只兜底 api-key。
 */
const showLegacyForm = computed(() => AUTH_MODE === 'apikey')

/** 跳 Casdoor OIDC 登录：returnTo 经 state 随 IdP 往返，回调后还原。成功则整页跳转、不回此处。 */
async function casdoorLogin(): Promise<void> {
  errorMsg.value = ''
  const t = tenant.value.trim()
  if (!t) {
    errorMsg.value = '请先输入租户 / 组织名（Casdoor org）'
    return
  }
  casdoorBusy.value = true
  try {
    await auth.startOidcLogin(t, sanitizeRedirect(route.query.redirect) ?? '/')
  } catch (e) {
    casdoorBusy.value = false
    errorMsg.value = loginErrorText(e)
  }
}

/**
 * 一键登录口令：仅从构建期注入的 VITE_DEMO_PASSWORD 读取——源码绝不内置任何明文口令。
 * 为空（生产默认）时优雅降级：演示账号仅预填用户名并聚焦密码框，由用户手动输入。
 */
const DEMO_PASSWORD: string = import.meta.env.VITE_DEMO_PASSWORD ?? ''
/** 是否显示"去注册"入口：仅当后端 public-config 明确开放注册时（fail-closed）。 */
const showRegister = computed(() => auth.publicConfig?.registrationEnabled === true)

const DEMO = [
  { username: 'alice', icon: '👑', label: '全权限', tenant: 'acme', desc: '全部能力 · chat/rag/agent/…', color: 'linear-gradient(135deg,#2d6cdf,#4f6ff0)', tag: '#2d6cdf', tagBg: '#e8f0ff' },
  { username: 'bob', icon: '💬', label: '只读对话', tenant: 'globex', desc: '仅对话 chat', color: 'linear-gradient(135deg,#06b6d4,#22d3ee)', tag: '#0891b2', tagBg: '#e0f7fb' },
  { username: 'analyst-a', icon: '📊', label: '数据分析', tenant: 'tenantA', desc: '对话 + 数据分析', color: 'linear-gradient(135deg,#7c3aed,#a855f7)', tag: '#7c3aed', tagBg: '#f1e9ff' },
]

const FEATURES = [
  { icon: '💬', title: '对话 · 检索增强', desc: '多轮记忆 · 混合 RAG · GraphRAG' },
  { icon: '🤖', title: '智能体编排', desc: 'ReAct · 多 Agent DAG · 人在环' },
  { icon: '🎙️', title: '多模态 · 语音', desc: '视觉描述 · 语音闭环 ASR→TTS' },
]

/** 优先展示后端人话 message（用户名或密码错误 / 账号禁用 / 登录过于频繁），否则退回通用翻译。 */
function loginErrorText(e: unknown): string {
  if (e instanceof ApiError && e.body && typeof e.body === 'object') {
    const msg = (e.body as Record<string, unknown>).message
    if (typeof msg === 'string' && msg.trim()) return msg
  }
  return humanizeError(e)
}

async function doLogin(u: string, p: string): Promise<void> {
  errorMsg.value = ''
  await auth.login(u, p)
  void catalog.refreshLive() // 登录后触发一次 live discovery（best-effort）
  const redirect = sanitizeRedirect(route.query.redirect) ?? '/'
  await router.replace(redirect)
}

async function submit(): Promise<void> {
  errorMsg.value = ''
  if (!username.value.trim() || !password.value) {
    errorMsg.value = '请输入用户名和密码。'
    return
  }
  submitting.value = true
  try {
    await doLogin(username.value, password.value)
  } catch (e) {
    errorMsg.value = loginErrorText(e)
  } finally {
    submitting.value = false
  }
}

async function demoLogin(u: string): Promise<void> {
  if (busy.value) return
  errorMsg.value = ''
  username.value = u
  // 无注入口令（生产默认）→ 优雅降级：仅预填用户名并聚焦密码框，由用户手动输入后自行提交。
  if (!DEMO_PASSWORD) {
    await nextTick()
    passwordEl.value?.focus()
    return
  }
  pending.value = u
  try {
    await doLogin(u, DEMO_PASSWORD)
  } catch (e) {
    errorMsg.value = loginErrorText(e)
  } finally {
    pending.value = null
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
      <span class="lp-blob lp-blob-5" />
    </div>

    <div class="lp-stage">
      <div class="lp-card">
        <!-- 品牌区（宽屏）-->
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

        <!-- 表单区 -->
        <section class="lp-form">
          <div class="lp-compact-head">
            <div class="lp-compact-logo">◆ 能力控制台</div>
            <div class="lp-compact-tag">一体化能力试用台</div>
          </div>

          <h1 class="lp-title">登录</h1>
          <p class="lp-subtitle">内部试用台 · 登录后即可体验全部能力</p>

          <!-- Casdoor OIDC 登录（oidc/dual 模式）：先选租户(org)，再整页跳转 IdP（方案C shared app）。 -->
          <div v-if="OIDC_ENABLED" class="lp-input-wrap">
            <span class="lp-input-ico" aria-hidden="true">🏢</span>
            <input
              v-model="tenant"
              class="lp-input"
              type="text"
              autocomplete="organization"
              placeholder="租户 / 组织名（如 acme）"
              :disabled="casdoorBusy"
              @keyup.enter="casdoorLogin"
            />
          </div>
          <button
            v-if="OIDC_ENABLED"
            type="button"
            class="lp-btn lp-btn--casdoor"
            :disabled="casdoorBusy"
            @click="casdoorLogin"
          >
            <span v-if="casdoorBusy" class="lp-spin" aria-hidden="true" />
            {{ casdoorBusy ? '正在跳转 Casdoor…' : '用 Casdoor 登录' }}
          </button>
          <div v-if="OIDC_ENABLED && showLegacyForm" class="lp-divider"><span>或用账号密码登录</span></div>

          <div v-if="errorMsg" class="lp-error" role="alert">
            <span aria-hidden="true">⚠️</span><span>{{ errorMsg }}</span>
          </div>

          <form v-if="showLegacyForm" class="lp-fields" @submit.prevent="submit">
            <div class="lp-field">
              <label class="lp-label" for="login-username">用户名</label>
              <div class="lp-input-wrap">
                <span class="lp-input-ico" aria-hidden="true">👤</span>
                <input
                  id="login-username"
                  v-model="username"
                  class="lp-input"
                  type="text"
                  autocomplete="username"
                  spellcheck="false"
                  :disabled="busy"
                  placeholder="如 alice"
                />
              </div>
            </div>

            <div class="lp-field">
              <label class="lp-label" for="login-password">密码</label>
              <div class="lp-input-wrap">
                <span class="lp-input-ico" aria-hidden="true">🔒</span>
                <input
                  id="login-password"
                  ref="passwordEl"
                  v-model="password"
                  class="lp-input lp-input--pw"
                  :type="showPassword ? 'text' : 'password'"
                  autocomplete="current-password"
                  :disabled="busy"
                  placeholder="密码"
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

            <button type="submit" class="lp-btn" :disabled="busy">
              <span v-if="submitting" class="lp-spin" aria-hidden="true" />
              {{ submitting ? '登录中…' : '登 录' }}
            </button>
          </form>

          <!-- 演示账号卡：仅 legacy 表单可见 + DEMO_LOGIN_ENABLED 时渲染；口令由部署注入，源码不内置明文。 -->
          <template v-if="showLegacyForm && DEMO_LOGIN_ENABLED">
            <div class="lp-divider"><span>或用演示账号快速登录</span></div>

            <div class="lp-demos">
              <button
                v-for="u in DEMO"
                :key="u.username"
                type="button"
                class="lp-demo"
                :disabled="busy"
                :style="{ opacity: pending && pending !== u.username ? 0.45 : 1 }"
                :aria-label="`使用演示账号 ${u.username}（${u.label}）登录`"
                @click="demoLogin(u.username)"
              >
                <span class="lp-avatar" :style="{ background: u.color }">
                  {{ pending === u.username ? '⏳' : u.icon }}
                </span>
                <span class="lp-demo-main">
                  <span class="lp-demo-name">
                    {{ u.username }}
                    <span class="lp-tag" :style="{ color: u.tag, background: u.tagBg }">{{ u.label }}</span>
                  </span>
                  <span class="lp-demo-desc">{{ u.desc }} · 租户 {{ u.tenant }}</span>
                </span>
                <span class="lp-arrow" aria-hidden="true">›</span>
              </button>
            </div>

            <p class="lp-foot-note">
              {{
                DEMO_PASSWORD
                  ? '点击任意演示账号即可快速登录（口令由部署注入）。'
                  : '点击演示账号将预填用户名，请手动输入密码后登录。'
              }}
            </p>
          </template>

          <p v-if="showLegacyForm && showRegister" class="lp-reg">
            还没有账号？<RouterLink class="lp-reg-link" :to="{ name: 'register' }">去注册</RouterLink>
          </p>
        </section>
      </div>
      <div class="lp-foot">内部试用台 · 数据仅供演示</div>
    </div>
  </div>
</template>

<style scoped>
/* 自成一体的亮色配色（不跟随应用暗色主题，保证登录页始终明亮高级）。 */
.lp-root {
  --lp-primary: #2d6cdf;
  --lp-ink: #1e2a44;
  --lp-muted: #5b6b8c;
  --lp-subtle: #8b98b4;
  --lp-line: rgba(45, 108, 223, 0.14);
  position: relative;
  flex: 1;
  min-height: 100vh;
  width: 100%;
  overflow-y: auto;
  display: flex;
  background: linear-gradient(160deg, #eaf1ff 0%, #f2f0ff 46%, #e9fbff 100%);
  color: var(--lp-ink);
  font-feature-settings: 'tnum';
}

/* 极光背景：多枚模糊色团缓慢漂浮 */
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
.lp-blob-5 {
  width: 400px; height: 400px; top: 34%; left: 40%;
  background: radial-gradient(circle at center, rgba(79, 124, 255, 0.48), rgba(79, 124, 255, 0) 70%);
  animation: lp-float-b 31s ease-in-out infinite alternate;
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

/* 舞台 + 玻璃卡片 */
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

/* 品牌区 */
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

/* 表单区 */
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

.lp-fields { margin-top: 20px; display: flex; flex-direction: column; gap: 15px; }
.lp-field { display: flex; flex-direction: column; gap: 6px; }
.lp-label { font-size: 12.5px; font-weight: 600; color: var(--lp-muted); }
.lp-input-wrap { position: relative; display: flex; align-items: center; }
.lp-input-ico {
  position: absolute;
  left: 12px;
  font-size: 14px;
  opacity: 0.6;
  pointer-events: none;
}
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
/* Casdoor 登录按钮：紧随副标题，留出间距（复用 .lp-btn 渐变样式）。 */
.lp-btn--casdoor { margin-top: 20px; }
.lp-spin {
  width: 15px; height: 15px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  animation: lp-rotate 0.7s linear infinite;
}
@keyframes lp-rotate { to { transform: rotate(360deg); } }

.lp-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0;
  color: var(--lp-subtle);
  font-size: 12px;
}
.lp-divider::before,
.lp-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--lp-line);
}

.lp-demos { display: flex; flex-direction: column; gap: 9px; }
.lp-demo {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 12px;
  border: 1px solid rgba(45, 108, 223, 0.13);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.5);
  cursor: pointer;
  text-align: left;
  transition: transform 0.18s ease, border-color 0.18s ease, background 0.18s ease, box-shadow 0.18s ease;
}
.lp-demo:not(:disabled):hover {
  border-color: rgba(45, 108, 223, 0.5);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 8px 20px rgba(45, 108, 223, 0.16);
  transform: translateY(-1px);
}
.lp-demo:not(:disabled):hover .lp-arrow { transform: translateX(3px); color: var(--lp-primary); }
.lp-demo:disabled { cursor: not-allowed; }
.lp-avatar {
  width: 34px; height: 34px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 16px; color: #fff; flex: 0 0 auto;
  box-shadow: 0 2px 6px rgba(26, 54, 110, 0.2);
}
.lp-demo-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.lp-demo-name { font-size: 14px; font-weight: 600; color: var(--lp-ink); display: flex; align-items: center; gap: 7px; }
.lp-tag { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 999px; }
.lp-demo-desc { font-size: 12px; color: var(--lp-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lp-arrow { color: var(--lp-subtle); font-size: 18px; flex: 0 0 auto; transition: transform 0.18s ease, color 0.18s ease; }

.lp-foot-note { font-size: 12px; color: var(--lp-subtle); margin-top: 14px; }
.lp-reg { font-size: 13px; color: var(--lp-muted); margin-top: 16px; text-align: center; }
.lp-reg-link { color: var(--lp-primary); font-weight: 600; }
.lp-reg-link:hover { text-decoration: underline; }
.lp-foot { font-size: 12px; color: rgba(30, 50, 90, 0.5); }

/* 窄屏：收起品牌区，表单卡片自带紧凑品牌头 */
@media (max-width: 860px) {
  .lp-brand { display: none; }
  .lp-card { max-width: 440px; }
  .lp-compact-head { display: block; }
}
@media (max-width: 480px) {
  .lp-form { padding: 30px 22px 26px; }
}

@media (prefers-reduced-motion: reduce) {
  .lp-blob, .lp-card, .lp-spin { animation: none !important; }
  .lp-demo, .lp-arrow, .lp-btn, .lp-input { transition: none !important; }
}
</style>
