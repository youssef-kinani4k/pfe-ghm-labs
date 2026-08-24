/** Calquee sur le record PageResponse du backend, ecrite une fois pour toutes les listes. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
