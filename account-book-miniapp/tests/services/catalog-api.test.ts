import { describe, expect, it, vi } from 'vitest';
import { CatalogApi } from '../../miniprogram/services/catalog';
describe('catalog wire contract', () => {
    it('encodes list filters and sends only editable category fields', async () => {
        const request = vi.fn().mockResolvedValue({ items: [] });
        const api = new CatalogApi({ request } as never);
        await api.categories('EXPENSE', 'DISABLED');
        await api.updateCategory(7, { name: '餐饮', icon: null, color: '#112233', sortNo: 0, status: 'ACTIVE' });
        expect(request.mock.calls).toEqual([
            [{ method: 'GET', path: '/api/v1/categories?entryType=EXPENSE&status=DISABLED' }],
            [{ method: 'PUT', path: '/api/v1/categories/7', body: { name: '餐饮', icon: null, color: '#112233', sortNo: 0, status: 'ACTIVE' } }],
        ]);
    });
    it('sends account version on update/delete and never sends immutable fields', async () => {
        const request = vi.fn().mockResolvedValue({ items: [] });
        const api = new CatalogApi({ request } as never);
        await api.updateAccount(7, { name: '现金', sortNo: 0, status: 'ACTIVE', version: 2 });
        await api.deleteAccount(7, 2);
        expect(request.mock.calls).toEqual([
            [{ method: 'PUT', path: '/api/v1/accounts/7', body: { name: '现金', sortNo: 0, status: 'ACTIVE', version: 2 } }],
            [{ method: 'DELETE', path: '/api/v1/accounts/7?version=2' }],
        ]);
    });
    it.each([0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])('rejects unsafe id %s before request', async (id) => {
        const request = vi.fn();
        const api = new CatalogApi({ request } as never);
        await expect(api.category(id)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' });
        await expect(api.deleteAccount(id, 0)).rejects.toMatchObject({ code: 'CLIENT_VALIDATION_FAILED' });
        expect(request).not.toHaveBeenCalled();
    });
});

