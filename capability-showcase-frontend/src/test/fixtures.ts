import type { Catalog } from '../types/catalog'
import catalogJson from '../../public/catalog.json'

/**
 * 测试夹具：加载真实生成的静态目录（public/catalog.json），
 * 保证专用视图测试用的能力 id 与生产目录一致，避免手写 fixture 漂移。
 */
export function loadCatalog(): Catalog {
  return catalogJson as unknown as Catalog
}
