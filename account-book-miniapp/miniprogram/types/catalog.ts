export type ResourceStatus = 'ACTIVE' | 'DISABLED';
export type EntryType = 'INCOME' | 'EXPENSE';
export type AccountType = 'CASH' | 'WECHAT' | 'BANK' | 'ALIPAY' | 'OTHER';
export interface Category {
    id: number;
    entryType: EntryType;
    name: string;
    icon: string | null;
    color: string | null;
    sortNo: number;
    systemDefault: boolean;
    status: ResourceStatus;
}
export interface Account {
    id: number;
    name: string;
    accountType: AccountType;
    initialBalance: string;
    currentBalance: string;
    sortNo: number;
    status: ResourceStatus;
    version: number;
}
export interface ItemList<T> {
    items: T[];
}
export interface CreateCategory {
    entryType: EntryType;
    name: string;
    icon: string | null;
    color: string | null;
    sortNo: number;
    status: ResourceStatus;
}
export type UpdateCategory = Omit<CreateCategory, 'entryType'>;
export interface CreateAccount {
    name: string;
    accountType: AccountType;
    initialBalance: string;
    sortNo: number;
    status: ResourceStatus;
}
export interface UpdateAccount {
    name: string;
    sortNo: number;
    status: ResourceStatus;
    version: number;
}

