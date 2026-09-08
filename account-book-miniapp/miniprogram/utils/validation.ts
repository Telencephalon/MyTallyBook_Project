export type ValidationResult =
  | { valid: true; value: string }
  | { valid: false; message: string }

export function validateBootstrapKey(value: string): ValidationResult {
  const normalized = value.trim()
  if (normalized.length < 20 || normalized.length > 256) {
    return {
      valid: false,
      message: '初始化口令长度必须为20至256个字符',
    }
  }
  return { valid: true, value: normalized }
}

export function validateNickname(value: string): ValidationResult {
  const normalized = value.trim()
  if (normalized.length < 1 || normalized.length > 64) {
    return {
      valid: false,
      message: '昵称长度必须为1至64个字符',
    }
  }
  return { valid: true, value: normalized }
}
