'use client';

import { Component, ReactNode } from 'react';

interface Props {
  children: ReactNode;
  /** Optional label so logs identify which boundary tripped. */
  label?: string;
}

interface State {
  err: Error | null;
}

/**
 * Class-based error boundary — Next.js's default error UI just says "Application
 * error: client-side exception" without telling the user (or us) what failed.
 * This boundary surfaces the message + stack and lets the rest of the app
 * keep functioning instead of unmounting the whole page.
 */
export class RiskDeskErrorBoundary extends Component<Props, State> {
  state: State = { err: null };

  static getDerivedStateFromError(err: Error): State {
    return { err };
  }

  componentDidCatch(err: Error, info: { componentStack?: string }): void {
    console.error('[RiskDesk] render error', this.props.label ?? '', err, info?.componentStack);
  }

  render(): ReactNode {
    if (this.state.err) {
      return (
        <div
          style={{
            padding: 16,
            margin: 16,
            border: '1px solid #f87171',
            background: 'rgba(248, 113, 113, 0.08)',
            borderRadius: 6,
            fontFamily:
              'var(--rd-font-jetbrains, "JetBrains Mono"), ui-monospace, monospace',
            fontSize: 12,
            color: '#fca5a5',
            whiteSpace: 'pre-wrap',
            maxHeight: '60vh',
            overflow: 'auto',
          }}
        >
          <div style={{ fontWeight: 700, marginBottom: 8, color: '#f87171' }}>
            RiskDesk render error{this.props.label ? ` — ${this.props.label}` : ''}
          </div>
          <div style={{ marginBottom: 8 }}>{this.state.err.message}</div>
          {this.state.err.stack && (
            <div style={{ opacity: 0.7, fontSize: 11 }}>{this.state.err.stack}</div>
          )}
          <button
            type="button"
            onClick={() => this.setState({ err: null })}
            style={{
              marginTop: 12,
              padding: '4px 10px',
              border: '1px solid #f87171',
              background: 'transparent',
              color: '#f87171',
              borderRadius: 4,
              fontSize: 11,
              cursor: 'pointer',
            }}
          >
            Retry
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
