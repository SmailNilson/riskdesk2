'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
} from 'react';

export interface PaletteAction {
  id: string;
  label: string;
  /** Shown right-aligned in muted color, before the shortcut chip. */
  hint?: string;
  /** Shortcut chip text, e.g. "1", "⇧B". */
  shortcut?: string;
  /** Header label for grouping (e.g. "Instrument", "Order"). */
  group?: string;
  /** Styled in red; v1 fires immediately (no hold-to-confirm). */
  destructive?: boolean;
  run: () => void | Promise<void>;
}

export interface CommandPaletteProps {
  actions: PaletteAction[];
  /** When provided, hooks up Cmd/Ctrl+K toggle internally. Default true. */
  installShortcut?: boolean;
  /** If you want to control open state externally, pass these together. */
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  /** Placeholder for the search input. */
  placeholder?: string;
}

interface ScoredAction {
  action: PaletteAction;
  score: number;
  index: number;
}

/**
 * Case-insensitive subsequence match. Returns the position of the first
 * matched character in the haystack, or -1 when the needle does not match.
 * Empty needles match at position 0 — i.e. every action is visible when no
 * query is typed.
 */
function subsequenceScore(haystack: string, needle: string): number {
  if (needle.length === 0) return 0;
  const h = haystack.toLowerCase();
  const n = needle.toLowerCase();
  let firstMatch = -1;
  let hi = 0;
  for (let ni = 0; ni < n.length; ni += 1) {
    const ch = n.charAt(ni);
    let found = -1;
    while (hi < h.length) {
      if (h.charAt(hi) === ch) {
        found = hi;
        hi += 1;
        break;
      }
      hi += 1;
    }
    if (found === -1) return -1;
    if (ni === 0) firstMatch = found;
  }
  return firstMatch;
}

function SearchIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="text-zinc-500 shrink-0"
    >
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  );
}

export default function CommandPalette({
  actions,
  installShortcut = true,
  open: openProp,
  onOpenChange,
  placeholder = 'Rechercher une action…',
}: CommandPaletteProps) {
  const isControlled = openProp !== undefined;
  const [internalOpen, setInternalOpen] = useState(false);
  const open = isControlled ? !!openProp : internalOpen;

  const setOpen = useCallback(
    (next: boolean) => {
      if (!isControlled) setInternalOpen(next);
      if (onOpenChange) onOpenChange(next);
    },
    [isControlled, onOpenChange],
  );

  const [query, setQuery] = useState('');
  const [highlight, setHighlight] = useState(0);
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const rowRefs = useRef<Array<HTMLDivElement | null>>([]);

  // Cmd/Ctrl+K toggle (and Escape close) at the window level.
  useEffect(() => {
    if (!installShortcut) return undefined;
    if (typeof window === 'undefined') return undefined;
    const handler = (e: KeyboardEvent) => {
      const key = e.key.toLowerCase();
      if ((e.metaKey || e.ctrlKey) && key === 'k') {
        e.preventDefault();
        setOpen(!open);
        return;
      }
      if (key === 'escape' && open) {
        e.preventDefault();
        setOpen(false);
      }
    };
    window.addEventListener('keydown', handler);
    return () => {
      window.removeEventListener('keydown', handler);
    };
  }, [installShortcut, open, setOpen]);

  // Reset query + highlight whenever the palette opens; auto-focus the input.
  useEffect(() => {
    if (!open) return;
    setQuery('');
    setHighlight(0);
    // Defer focus until after the dialog is in the DOM.
    const id = window.setTimeout(() => {
      inputRef.current?.focus();
    }, 0);
    return () => window.clearTimeout(id);
  }, [open]);

  const filtered = useMemo<ScoredAction[]>(() => {
    const trimmed = query.trim();
    const scored: ScoredAction[] = [];
    for (let i = 0; i < actions.length; i += 1) {
      const a = actions[i];
      const haystack = `${a.label} ${a.hint ?? ''} ${a.group ?? ''}`;
      const score = subsequenceScore(haystack, trimmed);
      if (score >= 0) scored.push({ action: a, score, index: i });
    }
    scored.sort((x, y) => (x.score - y.score) || (x.index - y.index));
    return scored;
  }, [actions, query]);

  // Clamp highlight when results change.
  useEffect(() => {
    if (filtered.length === 0) {
      if (highlight !== 0) setHighlight(0);
      return;
    }
    if (highlight > filtered.length - 1) setHighlight(filtered.length - 1);
  }, [filtered.length, highlight]);

  // Keep the highlighted row in view.
  useEffect(() => {
    if (!open) return;
    const row = rowRefs.current[highlight];
    if (row && typeof row.scrollIntoView === 'function') {
      row.scrollIntoView({ block: 'nearest' });
    }
  }, [highlight, open]);

  const runAction = useCallback(
    async (action: PaletteAction) => {
      setOpen(false);
      try {
        await action.run();
      } catch {
        // Swallow — caller owns error handling. The palette must not crash.
      }
    },
    [setOpen],
  );

  const onInputKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlight((h) => (filtered.length === 0 ? 0 : Math.min(h + 1, filtered.length - 1)));
      return;
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlight((h) => Math.max(0, h - 1));
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      const chosen = filtered[highlight];
      if (chosen) {
        void runAction(chosen.action);
      }
      return;
    }
    if (e.key === 'Escape') {
      e.preventDefault();
      setOpen(false);
    }
    // Tab intentionally NOT trapped.
  };

  const onBackdropClick = (e: ReactMouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) setOpen(false);
  };

  if (!open) return null;

  // Reset row refs each render — array length must match filtered length.
  rowRefs.current = [];

  let lastGroup: string | undefined;

  return (
    <div
      className="fixed inset-0 z-50 bg-black/60 flex items-start justify-center pt-32 px-4"
      onMouseDown={onBackdropClick}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Palette de commandes"
        className="bg-zinc-900 border border-zinc-800 rounded-lg shadow-2xl w-[640px] max-w-full max-h-[60vh] flex flex-col overflow-hidden"
      >
        <div className="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
          <SearchIcon />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => {
              setQuery(e.target.value);
              setHighlight(0);
            }}
            onKeyDown={onInputKeyDown}
            placeholder={placeholder}
            aria-controls="cmdp-list"
            aria-autocomplete="list"
            className="bg-transparent flex-1 outline-none text-white placeholder-zinc-500 text-sm"
          />
        </div>

        <div
          id="cmdp-list"
          role="listbox"
          ref={listRef}
          className="overflow-y-auto flex-1"
        >
          {filtered.length === 0 ? (
            <div className="text-zinc-500 text-sm text-center py-10">
              Aucune action trouvée
            </div>
          ) : (
            filtered.map((item, idx) => {
              const { action } = item;
              const group = action.group;
              const showHeader = group !== undefined && group !== lastGroup;
              lastGroup = group;
              const isHighlighted = idx === highlight;
              const rowClass = [
                'flex items-center gap-3 px-4 py-2 cursor-pointer',
                isHighlighted ? 'bg-zinc-800' : '',
                action.destructive ? 'text-red-400' : 'text-white',
              ]
                .filter(Boolean)
                .join(' ');
              return (
                <div key={action.id}>
                  {showHeader ? (
                    <div className="text-[10px] uppercase tracking-widest text-zinc-500 px-4 pt-3 pb-1">
                      {group}
                    </div>
                  ) : null}
                  <div
                    role="option"
                    aria-selected={isHighlighted}
                    ref={(el) => {
                      rowRefs.current[idx] = el;
                    }}
                    className={rowClass}
                    onMouseEnter={() => setHighlight(idx)}
                    onMouseDown={(e) => {
                      // Prevent the input from blurring before the click fires.
                      e.preventDefault();
                    }}
                    onClick={() => {
                      void runAction(action);
                    }}
                  >
                    <span className="flex-1 text-sm">{action.label}</span>
                    {action.hint ? (
                      <span className="text-zinc-500 text-xs">{action.hint}</span>
                    ) : null}
                    {action.shortcut ? (
                      <span className="bg-zinc-800 border border-zinc-700 px-1.5 py-0.5 rounded text-[10px] font-mono text-zinc-400">
                        {action.shortcut}
                      </span>
                    ) : null}
                  </div>
                </div>
              );
            })
          )}
        </div>

        <div className="border-t border-zinc-800 px-4 py-2 text-[10px] text-zinc-500">
          ↑↓ naviguer · ↵ exécuter · esc fermer
        </div>
      </div>
    </div>
  );
}
