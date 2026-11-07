from pathlib import Path
import sys
root = Path(sys.argv[1])
p = root / 'libavcodec/ac3enc.h'
s = p.read_text()
needle = '    int eac3_dependent_enabled;'
assert needle in s
s = s.replace(needle, '    uint8_t eac3_main_channel_map[6];\n' + needle)
p.write_text(s)
p = root / 'libavcodec/ac3enc.c'
s = p.read_text()
needle = '    s->channel_map  = ac3_enc_channel_map[s->channel_mode][s->lfe_on];'
assert needle in s
s = s.replace(needle, needle + """
    if (s->eac3_dependent_enabled) {
        AVChannelLayout main_layout = {0};
        av_channel_layout_from_mask(&main_layout, mask);
        for (int i = 0; i < s->channels; i++) {
            enum AVChannel channel = av_channel_layout_channel_from_index(
                &main_layout, s->channel_map[i]);
            int input_index = av_channel_layout_index_from_channel(&avctx->ch_layout, channel);
            av_assert0(input_index >= 0 && input_index < s->input_channels);
            s->eac3_main_channel_map[i] = input_index;
        }
        s->channel_map = s->eac3_main_channel_map;
        av_channel_layout_uninit(&main_layout);
    }
""")
p.write_text(s)
