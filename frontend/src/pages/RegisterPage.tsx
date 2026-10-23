import { usePreferences } from '../preferences/PreferencesProvider'
import { t, ui } from '../preferences/i18n'
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { register } from '../api/auth';
/**
 * 注册页（2026-10-11 邀请码准入：仅凭有效邀请码开放，注册成功后再登录）
 */
export default function RegisterPage() {
    usePreferences();
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [nickname, setNickname] = useState('');
    const [invitationCode, setInvitationCode] = useState('');
    const [error, setError] = useState('');
    const [done, setDone] = useState(false);
    const [loading, setLoading] = useState(false);
    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError('');
        setLoading(true);
        try {
            await register(username, password, nickname, invitationCode);
            setDone(true);
        }
        catch (err) {
            setError(err instanceof Error ? err.message : '注册失败');
        }
        finally {
            setLoading(false);
        }
    }
    return (<div className="login-page">
      {ui(done ? (<div className="login-card">
          <h2>{t("注册成功")}</h2>
          <p className="login-link">{t("账号已创建\uFF0C初始积分已到账\uFF0C请登录使用\u3002")}</p>
          <Link to="/login">
            <button type="button">{t("去登录")}</button>
          </Link>
        </div>) : (<form className="login-card" onSubmit={handleSubmit}>
          <h2>{t("TravelScope 注册")}</h2>
          {ui(error && <div className="login-error">{ui(error)}</div>)}
          <label>{t("用户名")}<input value={username} onChange={(e) => setUsername(e.target.value)} placeholder={t("请输入用户名")} autoComplete="username" required/>
          </label>
          <label>{t("密码")}<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder={t("请输入密码")} autoComplete="new-password" required/>
          </label>
          <label>{t("昵称\uFF08可选\uFF09")}<input value={nickname} onChange={(e) => setNickname(e.target.value)} placeholder={t("默认与用户名相同")} autoComplete="nickname"/>
          </label>
          <label>{t("邀请码")}<input value={invitationCode} onChange={(e) => setInvitationCode(e.target.value)} placeholder={t("请输入 8 位邀请码")} required/>
          </label>
          <button type="submit" disabled={loading}>
            {ui(loading ? t("注册中\u2026") : t("注册"))}
          </button>
          <p className="login-link">{t("已有账号\uFF1F")}<Link to="/login">{t("去登录")}</Link>
          </p>
        </form>))}
    </div>);
}
