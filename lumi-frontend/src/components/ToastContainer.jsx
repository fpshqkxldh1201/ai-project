import { useToast } from '../context/ToastContext.jsx'

export default function ToastContainer() {
  const { toasts } = useToast()
  return (
    <div className="toast-container">
      {toasts.map((t) => (
        <div
          key={t.id}
          className={'toast' + (t.status === 'success' ? ' toast-success' : '') + (t.leaving ? ' toast-leaving' : '')}
        >
          <span className="toast-title">{t.title}</span>
          <span className="toast-body">{t.detail}</span>
        </div>
      ))}
    </div>
  )
}
