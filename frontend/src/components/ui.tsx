import { forwardRef, type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode } from 'react';
import { AlertTriangle, ChevronDown, Code2, Inbox, LoaderCircle, RefreshCw } from 'lucide-react';
import { statusLabel } from '../shared/presentation';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({
  variant = 'primary',
  className = '',
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant }) {
  return (
    <button className={`rw-button rw-button--${variant} ${className}`} {...props}>
      {children}
    </button>
  );
}

export const IconButton = forwardRef<HTMLButtonElement, ButtonHTMLAttributes<HTMLButtonElement> & {
  label: string;
  children: ReactNode;
}>(function IconButton({ label, children, className = '', ...props }, ref) {
  return (
    <button ref={ref} className={`rw-icon-button ${className}`} aria-label={label} title={label} {...props}>
      {children}
    </button>
  );
});

export function Field({
  label,
  hint,
  error,
  children,
}: { label: string; hint?: string; error?: string; children: ReactNode }) {
  return (
    <label className="rw-field">
      <span className="rw-field__label">{label}</span>
      {children}
      {error ? <span className="rw-field__error">{error}</span> : hint ? <span className="rw-field__hint">{hint}</span> : null}
    </label>
  );
}

export function Input({ className = '', ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`rw-input ${className}`} {...props} />;
}

export function Textarea({ className = '', ...props }: React.TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={`rw-input rw-textarea ${className}`} {...props} />;
}

export function Select({ className = '', ...props }: React.SelectHTMLAttributes<HTMLSelectElement>) {
  return <select className={`rw-input rw-select ${className}`} {...props} />;
}

export function Card({
  title,
  eyebrow,
  action,
  className = '',
  children,
}: {
  title?: ReactNode;
  eyebrow?: ReactNode;
  action?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={`rw-card ${className}`}>
      {(title || action) && (
        <header className="rw-card__header">
          <div>
            {eyebrow && <div className="rw-eyebrow">{eyebrow}</div>}
            {title && <h2>{title}</h2>}
          </div>
          {action}
        </header>
      )}
      {children}
    </section>
  );
}

export function Tag({ children, tone = 'neutral' }: { children: ReactNode; tone?: string }) {
  return <span className={`rw-tag rw-tag--${tone}`}>{children}</span>;
}

export function StatusTag({ status, technical = false }: { status: string; technical?: boolean }) {
  const tone = /FAILED|REJECTED|CONTRADICT/.test(status)
    ? 'danger'
    : /COMPLETED|VERIFIED|CONFIRMED|PUBLISHED|SUPPORTED/.test(status)
      ? 'success'
      : /PENDING|REVIEW|COLLECTING|RUNNING|PARSING/.test(status)
        ? 'warning'
        : 'neutral';
  return <Tag tone={tone}>{statusLabel(status)}{technical ? ` · ${status}` : ''}</Tag>;
}

export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
}: { eyebrow?: ReactNode; title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="rw-page-header">
      <div>
        {eyebrow && <div className="rw-eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {actions && <div className="rw-page-header__actions">{actions}</div>}
    </header>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: { title: string; description: string; action?: ReactNode }) {
  return (
    <div className="rw-state">
      <Inbox aria-hidden />
      <strong>{title}</strong>
      <p>{description}</p>
      {action}
    </div>
  );
}

export function LoadingState({ label = '正在加载' }: { label?: string }) {
  return (
    <div className="rw-state" role="status" aria-live="polite">
      <LoaderCircle className="rw-spin" aria-hidden />
      <strong>{label}</strong>
      <p>正在同步工作空间中的最新快照。</p>
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : '发生未知错误';
  const requestId = typeof error === 'object' && error && 'requestId' in error
    ? String((error as { requestId?: unknown }).requestId ?? '')
    : '';
  return (
    <div className="rw-state rw-state--error" role="alert">
      <AlertTriangle aria-hidden />
      <strong>暂时无法读取数据</strong>
      <p>{message}</p>
      {requestId && <Mono>请求 ID：{requestId}</Mono>}
      {onRetry && <Button variant="secondary" onClick={onRetry}><RefreshCw size={15} />重试</Button>}
    </div>
  );
}

export function Metric({ label, value, meta, tone }: {
  label: string;
  value: ReactNode;
  meta?: ReactNode;
  tone?: string;
}) {
  return (
    <div className={`rw-metric ${tone ? `rw-metric--${tone}` : ''}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      {meta && <small>{meta}</small>}
    </div>
  );
}

export function Progress({ value, tone = 'brand', label }: { value: number; tone?: string; label: string }) {
  const normalized = Math.max(0, Math.min(1, value));
  return (
    <div className="rw-progress" aria-label={label} role="progressbar" aria-valuenow={Math.round(normalized * 100)} aria-valuemin={0} aria-valuemax={100}>
      <span className={`rw-progress__fill rw-progress__fill--${tone}`} style={{ width: `${normalized * 100}%` }} />
    </div>
  );
}

export function Mono({ children }: { children: ReactNode }) {
  return <code className="rw-mono">{children}</code>;
}

export function TechnicalDetails({
  summary = '技术详情',
  children,
  className = '',
}: { summary?: string; children: ReactNode; className?: string }) {
  return (
    <details className={`rw-technical-details ${className}`}>
      <summary><Code2 size={14} aria-hidden />{summary}<ChevronDown size={14} className="rw-technical-details__chevron" aria-hidden /></summary>
      <div className="rw-technical-details__content">{children}</div>
    </details>
  );
}
