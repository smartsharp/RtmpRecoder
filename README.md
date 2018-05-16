2018.5.15  
1. 使用 ImageReader 的 Surface 作为录屏输入，然后将输入数据通过 FFmpegRTMPRecorder 发送出去，但是不成功，只有声音，没有画面。
2. 使用 MediaCodec 的Surface 作为录屏输入，此输入数据已经是 h264 压缩格式，无法传送给 FFmpegRTMPRecorder，没有测试成功；
3. 使用 FFmpegRTMPRecorder 录屏推流没有成功，成功的例子是使用腾讯直播SDK，见我的另一个例子：https://github.com/smartsharp/TestTencentRtmp。
4. 使用心得： https://www.jianshu.com/p/2e21d81b350c










# RtmpRecoder

Record camera and push stream to rtmp server.

# 直播解决方案
[视频直播解决方案](http://www.tangokk.com/blog/2016/01/29/%E7%9B%B4%E6%92%AD%E8%A7%A3%E5%86%B3%E6%96%B9%E6%A1%88-%E6%90%AD%E5%BB%BA%E4%BD%A0%E8%87%AA%E5%B7%B1%E7%9A%84%E7%9B%B4%E6%92%AD%E5%B9%B3%E5%8F%B0/)

# 直播录制客户端
[Android使用FFMpeg实现推送视频直播流到服务器](http://www.tangokk.com/blog/2016/01/30/Android%E4%BD%BF%E7%94%A8FFMpeg%E5%AE%9E%E7%8E%B0%E6%8E%A8%E9%80%81%E8%A7%86%E9%A2%91%E7%9B%B4%E6%92%AD%E6%B5%81%E5%88%B0%E6%9C%8D%E5%8A%A1%E5%99%A8/)

# 直播流播放
[如何在网页端和移动端播放Rtmp和hls视频流](http://www.tangokk.com/blog/2016/01/30/%E5%9C%A8%E5%90%84%E7%AB%AF%E5%AE%9E%E7%8E%B0Rtmp%E5%92%8Chls%E6%B5%81%E8%A7%86%E9%A2%91%E7%9A%84%E6%92%AD%E6%94%BE/)

