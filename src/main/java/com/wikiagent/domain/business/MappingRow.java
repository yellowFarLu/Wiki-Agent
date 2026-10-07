package com.wikiagent.domain.business;

/**
 * 小包号 → 大包号的映射行（清关映射表 Excel 的一行）。
 *
 * @param smallPackageNo 小包号
 * @param bigPackageNo   大包号
 */
public record MappingRow(String smallPackageNo, String bigPackageNo) {
}
