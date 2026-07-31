/**
 * Exam Point Reader - Built-in example data
 *
 * Provides sample TXT-formatted content for the subject parser and for
 * offline testing of the reader. Every entry's `content` field follows the
 * tagged TXT format understood by subjectParser.js, so they can be passed
 * directly to parseContent() / formatForDisplay().
 *
 * Entry shape:
 *   {
 *     id:      string  unique identifier
 *     name:    string  display name shown in lists
 *     type:    string  "poem" | "history"
 *     content: string  full TXT-format source text
 *   }
 */

export const builtinExamples = [
  // ==================== Poetry / classical text ====================
  {
    id: 'builtin_poem_guanju',
    name: '关雎',
    type: 'poem',
    content: `@type:poem
@title:关雎
@author:佚名
@dynasty:先秦
@original
关关雎鸠，在河之洲。
窈窕淑女，君子好逑。
参差荇菜，左右流之。
窈窕淑女，寤寐求之。
求之不得，寤寐思服。
悠哉悠哉，辗转反侧。
参差荇菜，左右采之。
窈窕淑女，琴瑟友之。
参差荇菜，左右芼之。
窈窕淑女，钟鼓乐之。
@end
@translation
关关鸣叫的雎鸠，栖息在河中沙洲。
美丽贤淑的女子，是君子的好配偶。
参差不齐的荇菜，在船的左右两边捞取。
美丽贤淑的女子，日日夜夜都在追求。
追求不到，日夜思念她。
思念悠悠，翻来覆去睡不着。
参差不齐的荇菜，在船的左右两边采摘。
美丽贤淑的女子，弹琴鼓瑟亲近她。
参差不齐的荇菜，在船的左右两边挑选。
美丽贤淑的女子，敲钟击鼓使她快乐。
@end
@knowledge
1. 《诗经》是中国最早的诗歌总集，收录西周至春秋诗歌305篇，又称"诗三百"。
2. 《诗经》分为"风""雅""颂"三部分，表现手法为"赋""比""兴"。
3. "关关雎鸠，在河之洲"运用"兴"的手法，以雎鸠和鸣起兴，引出男女之情。
4. 全诗运用重章叠句，回环往复，增强了音乐性和节奏感。
@end`
  },
  {
    id: 'builtin_poem_jingyesi',
    name: '静夜思',
    type: 'poem',
    content: `@type:poem
@title:静夜思
@author:李白
@dynasty:唐
@original
床前明月光，疑是地上霜。
举头望明月，低头思故乡。
@end
@translation
明亮的月光洒在床前，地上好像泛起了一层白霜。
抬起头仰望天上的明月，低下头不禁思念起远方的故乡。
@end
@knowledge
1. 作者李白，字太白，号青莲居士，唐代伟大的浪漫主义诗人，被誉为"诗仙"。
2. "疑是地上霜"中的"疑"意为"好像"，运用比喻，将月光比作白霜，渲染出清冷氛围。
3. "举头"与"低头"两个动作对照，由景入情，情景交融，抒发浓烈的思乡之情。
4. 全诗语言朴素自然，却意味深长，是思乡诗的千古名篇。
@end`
  },
  {
    id: 'builtin_poem_denggquanquelou',
    name: '登鹳雀楼',
    type: 'poem',
    content: `@type:poem
@title:登鹳雀楼
@author:王之涣
@dynasty:唐
@original
白日依山尽，黄河入海流。
欲穷千里目，更上一层楼。
@end
@translation
夕阳依傍着远山渐渐落下，滔滔黄河向着大海奔流。
若想看到千里之外的景色，就要再登上更高的一层楼。
@end
@knowledge
1. 作者王之涣，唐代诗人。鹳雀楼故址在今山西永济蒲州镇。
2. "欲穷千里目，更上一层楼"蕴含"站得高才能看得远"的哲理，是千古传诵的名句。
3. 全诗前两句写景，气象宏大；后两句抒情说理，景理交融。
4. 诗中"依""尽""入""流"等动词准确生动，画面感极强。
@end`
  },
  {
    id: 'builtin_poem_chunxiao',
    name: '春晓',
    type: 'poem',
    content: `@type:poem
@title:春晓
@author:孟浩然
@dynasty:唐
@original
春眠不觉晓，处处闻啼鸟。
夜来风雨声，花落知多少。
@end
@translation
春天的夜晚睡得香甜，不知不觉天就亮了，醒来时到处都能听到鸟儿的啼叫。
回想昨夜阵阵风雨的声音，不知吹落了多少美丽的春花。
@end
@knowledge
1. 作者孟浩然，唐代山水田园诗人，与王维并称"王孟"。
2. "处处闻啼鸟"从听觉角度侧面描写春意盎然的景象。
3. 全诗不着一个"喜"字，却流露出对春天的喜爱与淡淡的惜春之情。
4. 语言自然平淡，韵味悠长，体现了孟诗"淡而有味"的风格。
@end`
  },

  // ==================== History ====================
  {
    id: 'builtin_history_dongzhou',
    name: '东周开始',
    type: 'history',
    content: `@type:history
@date:公元前770年
@event:周平王将都城由镐京迁至洛邑，史称"平王东迁"，东周自此开始，中国进入春秋战国时期。
@end`
  },
  {
    id: 'builtin_history_qintongyi',
    name: '秦统一六国',
    type: 'history',
    content: `@type:history
@date:公元前221年
@event:秦王嬴政先后灭掉韩、赵、魏、楚、燕、齐六国，完成统一大业，建立秦朝，定都咸阳，自称"始皇帝"。
@end`
  },
  {
    id: 'builtin_history_wenjing',
    name: '文景之治',
    type: 'history',
    content: `@type:history
@date:公元前180年—公元前141年
@event:西汉文帝、景帝统治时期，推行"休养生息"政策，轻徭薄赋、提倡节俭，社会经济恢复发展，史称"文景之治"。
@end`
  },
  {
    id: 'builtin_history_sichouzhilu',
    name: '丝绸之路',
    type: 'history',
    content: `@type:history
@date:公元前138年
@event:汉武帝派张骞出使西域，加强了汉朝与西域各国的联系，开辟了沟通东西方的"丝绸之路"，促进了中国与中亚、西亚的交流。
@end`
  }
]

export default builtinExamples
