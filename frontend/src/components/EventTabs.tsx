import { Activity, ClipboardClock, GitCompareArrows, Network, SearchCheck, Target } from 'lucide-react';
import { NavLink } from 'react-router-dom';

const tabs = [
  { suffix: '', label: '概览', icon: Activity, end: true },
  { suffix: '/investigation', label: '调查工作台', icon: SearchCheck },
  { suffix: '/hypotheses', label: '假设对比', icon: GitCompareArrows },
  { suffix: '/graph', label: '因果图', icon: Network },
  { suffix: '/next-evidence', label: '下一步取证', icon: Target },
  { suffix: '/audit', label: '审计', icon: ClipboardClock },
];

export function EventTabs({ eventId }: { eventId: string }) {
  const base = `/events/${eventId}`;
  return (
    <nav className="rw-event-tabs" aria-label="事件内导航">
      {tabs.map((tab) => {
        const Icon = tab.icon;
        return (
          <NavLink
            key={tab.suffix || 'overview'}
            to={`${base}${tab.suffix}`}
            end={tab.end}
            className={({ isActive }) => isActive ? 'is-active' : ''}
          >
            <Icon size={15} aria-hidden />
            <span>{tab.label}</span>
          </NavLink>
        );
      })}
    </nav>
  );
}
