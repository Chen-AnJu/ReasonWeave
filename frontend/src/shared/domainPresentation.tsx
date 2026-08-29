import { useQueries, useQuery } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { queries } from '../api/queries';
import { DomainPresentationContext } from './domainPresentationContext';
import { createDomainLabels } from './domainPresentationLabels';

export function DomainPresentationProvider({ children }: { children: ReactNode }) {
  const packs = useQuery(queries.domainPacks());
  const details = useQueries({
    queries: (packs.data ?? []).map((pack) => queries.domainPack(pack.key, pack.version)),
  });
  const labels = createDomainLabels(details.flatMap((query) => query.data ? [query.data] : []));
  return (
    <DomainPresentationContext.Provider value={labels}>
      {children}
    </DomainPresentationContext.Provider>
  );
}
