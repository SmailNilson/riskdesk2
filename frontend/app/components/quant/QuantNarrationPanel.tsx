'use client';

import type { QuantNarrationView } from './types';

interface Props {
  narration: QuantNarrationView | null | undefined;
}

/**
 * Renders the markdown narration produced by the backend QuantNarrator. We
 * intentionally do not depend on a markdown library to keep the bundle small —
 * the narrator output is well-known and only uses headings, bold, lists and
 * fenced code blocks.
 */
export default function QuantNarrationPanel({ narration }: Props) {
  if (!narration || !narration.markdown) {
    return (
      <p className="text-xs text-slate-500 italic">
        Narration en attente du prochain scan…
      </p>
    );
  }
  return (
    <article className="prose-quant text-sm text-slate-200 space-y-2">
      {renderMarkdown(narration.markdown)}
    </article>
  );
}

function renderMarkdown(md: string): JSX.Element[] {
  const lines = md.split('\n');
  const out: JSX.Element[] = [];
  let key = 0;
  let inCodeBlock = false;
  let codeBuffer: string[] = [];
  let listBuffer: string[] = [];

  const flushList = () => {
    if (listBuffer.length === 0) return;
    out.push(
      <ul key={key++} className="list-none pl-0 space-y-0.5">
        {listBuffer.map((item, i) => (
          <li key={i} dangerouslySetInnerHTML={{ __html: inlineMarkup(item) }} />
        ))}
      </ul>
    );
    listBuffer = [];
  };

  for (const raw of lines) {
    const line = raw;
    if (line.trim().startsWith('```')) {
      if (inCodeBlock) {
        out.push(
          <pre key={key++} className="bg-slate-950 text-emerald-300 text-xs p-2 rounded overflow-x-auto">
            {codeBuffer.join('\n')}
          </pre>
        );
        codeBuffer = [];
        inCodeBlock = false;
      } else {
        flushList();
        inCodeBlock = true;
      }
      continue;
    }
    if (inCodeBlock) {
      codeBuffer.push(line);
      continue;
    }
    if (line.startsWith('## ')) {
      flushList();
      out.push(<h2 key={key++} className="text-base font-semibold text-slate-100">{line.slice(3)}</h2>);
    } else if (line.startsWith('### ')) {
      flushList();
      out.push(<h3 key={key++} className="text-sm font-semibold text-slate-300 mt-2">{line.slice(4)}</h3>);
    } else if (line.startsWith('- ')) {
      listBuffer.push(line.slice(2));
    } else if (line.startsWith('> ')) {
      flushList();
      out.push(
        <blockquote key={key++} className="border-l-2 border-slate-600 pl-2 text-slate-400 italic">
          <span dangerouslySetInnerHTML={{ __html: inlineMarkup(line.slice(2)) }} />
        </blockquote>
      );
    } else if (line.trim() === '') {
      flushList();
    } else {
      flushList();
      out.push(
        <p key={key++} dangerouslySetInnerHTML={{ __html: inlineMarkup(line) }} />
      );
    }
  }
  flushList();
  return out;
}

/** Tiny inline markup converter: backticks → <code>, **bold** → <strong>. Sanitises angle brackets. */
function inlineMarkup(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code class="bg-slate-800 px-1 rounded text-xs">$1</code>');
}
