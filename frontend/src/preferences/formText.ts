import type { RequirementFormField } from '../types'
import { t, ui } from './i18n'
import type { MessageKey } from './messages'
const captions: Record<string, readonly [MessageKey,MessageKey]> = {
  destination:['目的地','您想去哪个城市？'],days:['游玩天数','几天？'],
  startDate:['出发日期','具体日期，或说「下周」「月底」'],fromCity:['出发城市','从哪里出发？'],
  budget:['预算范围','人均预算档位'],preference:['偏好','可多选，也可补充描述'],
  people:['人数','几人同行？'],special:['特殊需求','可不填，如不吃辣、带老人小孩'],
}
const options: Record<string,readonly string[]> = { budget:['人均500内','500-1000','1000-2000','2000以上'],preference:['自然风景','人文古迹','美食','亲子','购物','夜景','海滨','徒步'] }
export function fieldLabel(field: RequirementFormField) {
  return field.labelKey === `requirements.${field.name}.label` && captions[field.name] ? t(captions[field.name][0]) : ui(field.label)
}
export function fieldPlaceholder(field: RequirementFormField) {
  return field.placeholderKey === `requirements.${field.name}.placeholder` && captions[field.name] ? t(captions[field.name][1]) : ui(field.placeholder)
}
export function optionLabel(field: RequirementFormField, value: string) {
  const key=field.optionKeys?.[value], index=options[field.name]?.indexOf(value)
  return key === `requirements.${field.name}.option.${index}` && index != null && index >= 0 ? ui(options[field.name][index]) : ui(value)
}
