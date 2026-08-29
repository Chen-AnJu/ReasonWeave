import { useQuery } from '@tanstack/react-query';
import {
  BookOpen,
  Braces,
  Boxes,
  ClipboardList,
  FileSearch,
  LayoutDashboard,
  Menu,
  Plus,
} from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { queries } from '../api/queries';
import { ErrorState, IconButton, LoadingState } from '../components/ui';
import { DomainPresentationProvider } from '../shared/domainPresentation';

const navItems = [
  { to: 'overview', label: '工作台概览', icon: LayoutDashboard, asset: '/icons/nav-overview.svg' },
  { to: 'events', label: '事件中心', icon: ClipboardList, asset: '/icons/nav-events.svg' },
  { to: 'evidence', label: '证据库', icon: FileSearch, asset: '/icons/nav-evidence.svg' },
  { to: 'knowledge', label: '知识中心', icon: BookOpen, asset: '/icons/nav-knowledge.svg' },
  { to: 'domain-packs', label: '领域包', icon: Boxes, asset: '/icons/nav-domain-packs.svg' },
];

const developerItems = [
  { to: 'developer/api-playground', label: 'API 调试台', icon: Braces },
];

export function AppShell() {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const runtime = useQuery(queries.runtime());
  useEffect(() => {
    if (!menuOpen) return;
    const firstLink = sidebarRef.current?.querySelector<HTMLElement>('a');
    firstLink?.focus();
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false);
        menuButtonRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [menuOpen]);
  if (runtime.isPending) {
    return <main className="rw-full-state"><LoadingState label="正在读取本地实例" /></main>;
  }
  if (runtime.isError || !runtime.data) {
    return <main className="rw-full-state"><ErrorState error={runtime.error} onRetry={() => runtime.refetch()} /></main>;
  }
  const instanceName = runtime.data.instance_name || 'ReasonWeave';
  return (
    <div className="rw-shell">
      {menuOpen && <button className="rw-sidebar-backdrop" aria-label="关闭导航菜单" onClick={() => setMenuOpen(false)} />}
      <aside ref={sidebarRef} className={`rw-sidebar ${menuOpen ? 'is-open' : ''}`} aria-label="主导航">
        <div className="rw-sidebar__brand">
          <img className="rw-logo-full" src="/brand/reasonweave-logo-horizontal.svg" alt="ReasonWeave" />
          <img className="rw-logo-mark" src="/brand/reasonweave-mark.svg" alt="ReasonWeave" />
        </div>
        <nav className="rw-sidebar__nav">
          <div className="rw-nav-label">调查工作区</div>
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink key={item.to} aria-label={item.label} onClick={() => setMenuOpen(false)} to={`/${item.to}`} className={({ isActive }) => `rw-nav-item ${isActive ? 'is-active' : ''}`}>
                <img src={item.asset} alt="" className="rw-nav-dot" />
                <Icon size={18} strokeWidth={1.7} aria-hidden />
                <span>{item.label}</span>
              </NavLink>
            );
          })}
          <div className="rw-nav-label rw-nav-label--developer">开发者</div>
          {developerItems.map((item) => {
            const Icon = item.icon;
            return (
              <NavLink key={item.to} aria-label={item.label} onClick={() => setMenuOpen(false)} to={`/${item.to}`} className={({ isActive }) => `rw-nav-item ${isActive ? 'is-active' : ''}`}>
                <span className="rw-nav-dot rw-nav-dot--css" />
                <Icon size={18} strokeWidth={1.7} aria-hidden />
                <span>{item.label}</span>
              </NavLink>
            );
          })}
        </nav>
        <div className="rw-sidebar__bottom">
          <div className="rw-user">
            <div className="rw-user__avatar">R</div>
            <div className="rw-user__copy">
              <strong>{instanceName}</strong>
              <span>本地自托管实例</span>
            </div>
          </div>
        </div>
      </aside>

      <div className="rw-workspace">
        <header className="rw-topbar">
          <IconButton
            ref={menuButtonRef}
            label={menuOpen ? '关闭导航菜单' : '打开导航菜单'}
            className="rw-menu-button"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          ><Menu size={18} /></IconButton>
          <div className="rw-topbar__actions">
            <NavLink className="rw-quick-create" to="/events/new">
              <Plus size={16} />新建事件
            </NavLink>
          </div>
        </header>
        <main className="rw-main" id="main-content">
          <DomainPresentationProvider><Outlet /></DomainPresentationProvider>
        </main>
      </div>
    </div>
  );
}
