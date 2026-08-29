import { createContext, useContext } from 'react';
import { createDomainLabels, type DomainLabels } from './domainPresentationLabels';

export const DomainPresentationContext = createContext<DomainLabels>(createDomainLabels([]));

export function useDomainLabels() {
  return useContext(DomainPresentationContext);
}
