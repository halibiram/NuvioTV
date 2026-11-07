from pathlib import Path
import sys
p = Path(sys.argv[1]) / 'libavcodec/ac3dec.c'
s = p.read_text()
needle = """        av_channel_layout_from_mask(&avctx->ch_layout, channel_layout);

        for (ch = 0; ch < EAC3_MAX_CHANNELS; ch++) {"""
assert needle in s
s = s.replace(needle, """        av_channel_layout_from_mask(&avctx->ch_layout, channel_layout);

        /* Keep independent-channel identities when dependent channels change
         * their indices in the combined layout (5.1 side -> 7.1). */
        {
            AVChannelLayout independent_layout = {0};
            av_channel_layout_from_mask(&independent_layout, ich_layout);
            for (int i = 0; i < independent_layout.nb_channels; i++) {
                enum AVChannel channel = av_channel_layout_channel_from_index(&independent_layout, i);
                int index = av_channel_layout_index_from_channel(&avctx->ch_layout, channel);
                av_assert0(index >= 0 && index < EAC3_MAX_CHANNELS);
                extended_channel_map[index] = i;
            }
            av_channel_layout_uninit(&independent_layout);
        }

        for (ch = 0; ch < EAC3_MAX_CHANNELS; ch++) {""")
p.write_text(s)
