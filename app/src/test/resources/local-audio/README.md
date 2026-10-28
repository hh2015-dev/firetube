These tiny samples are synthetic 440 Hz tones generated for FireTube tests, not
third-party recordings. They are test resources only and are not packaged in APKs.
They may be distributed under the repository GPLv3 license.

Generated using the system FFmpeg (no FFmpeg library is added to the app):

    ffmpeg -f lavfi -i sine=frequency=440:sample_rate=8000:duration=0.15 \
      -metadata 'title=שָׁלוֹם العربية Ελληνικά English' [codec options] tone.[format]

Options: WAV pcm_s16le; MP3 libmp3lame 32k, ID3v2.3 (UTF-16 title);
M4A aac 32k; FLAC flac; OGG libvorbis. The tests verify container sniffing and tag
text; hardware decoding and audible playback require an Android device.
