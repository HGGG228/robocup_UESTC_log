# robocup_UESTC_log
这里用来存放电子神技的跑图结果与代码修改

# 接下来是在询问学长后对每个部分的讲解
  /Apollo/Apollo2022/adf-sample-agent-java-master 这个路径是官方给出的实例代码，可以不用管
  
  /Apollo/Apollo2022/rcrs-server-master/maps 这个路径下是当前的地图，这里可以确认一下今年的地图是否和往年有所区别
  
  /Apollo/code/UESTC2026_final_pro_yy/UESTC2026/src/main/java/sample_team 这个路径可以不用管，是官方提供的相关内容
  
  /Apollo/code/UESTC2026_final_pro_yy/UESTC2026/src/main/java/UESTC/module/algorithm 这个路径下为核心的算法（类似于函数包），这个是要主要优化的部分

  ATTENTION! 
  
  /Apollo/code/UESTC2026_final_pro_yy/UESTC2026/src/main/java/UESTC/module/complex 这个路径为智能体部分，是要重点优化的部分，里面有五个文件夹，只用修改其中的三个文件夹：<img width="937" height="234" alt="image" src="https://github.com/user-attachments/assets/a2919fe5-7958-450e-80bc-9eb9635844ef" />
文件夹内的就是不同行为的具体代码，老师们可以丢给ai看看具体每个部分有什么作用，好像一个叫做A*的文件是最重要的，但是由于为了降重改了文件名，可以分别看一下

# 接下来具体的跑图方式
# 在挂载智能体的时候：
  在这个路径下：/Apollo/code/UESTC2026_final_pro_yy/UESTC2026
  
  在终端中：
  
  ./gradlew clean
  
  ./gradlew build

  成功后的图片<img width="1140" height="937" alt="image" src="https://github.com/user-attachments/assets/58aabaa3-5536-4462-9619-800994c2f531" /><img width="1121" height="948" alt="image" src="https://github.com/user-attachments/assets/561ea26d-6411-4cf7-aef7-1eac146547d3" /><img width="1136" height="369" alt="image" src="https://github.com/user-attachments/assets/920c363f-32f3-4f90-86e3-e694c63158bc" />

  之后和PDF中一样
# 在运行服务器的时候：
  将bash start.sh -m ../maps/test/map -c ../maps/test/config中的test改为具体的名字（请查看文件夹）
  
  其余步骤不变

  这是挂载kobe地图的结果：
  <img width="2160" height="1379" alt="image" src="https://github.com/user-attachments/assets/28966b64-f4bf-4630-92c3-f0f8af8c6359" />

  这是对应的服务器的显示情况：<img width="1963" height="1222" alt="image" src="https://github.com/user-attachments/assets/b82766df-0c17-40f3-9a4e-698353f6c213" />
  注意这个程序在运行到第200次的时候会自己关闭，所以要蹲点一下

# 地图的补充
  压缩包中的map是不全的，更全的map我放到群文件里了，名字叫 rcrs-server-master11 ，请大家自己下载覆盖之前的压缩包 rcrs-server-master

# 传承中的分数
  这个截图中的成绩为学长继承给我们的时候跑图所对应的成绩，大家可以为参考，我在之后将再重新跑一遍并将对应的结果更新在这里，请大家自行查看

  这个是原版的成绩
<img width="259" height="160" alt="image" src="https://github.com/user-attachments/assets/ddd02f7c-561c-4035-80d2-03a809f0298e" />

  这个是改版的成绩
<img width="237" height="149" alt="image" src="https://github.com/user-attachments/assets/6bd71e70-e113-4ad9-a667-f42096a304a7" />

# HGGG电脑上跑的结果
  笨人发现在我的电脑上跑的分数和这两个成绩都不相同，原因未知（可能是我用的Kobe而不是kobe1），请一位小伙伴在自己电脑上跑一下这个代码，看看和我的成绩是不是一样的，谢谢啦
  
  kobe:
<img width="1080" height="690" alt="4e59b2f53877bd2ff3987b56fa7b9ba2" src="https://github.com/user-attachments/assets/da8ca815-83f5-422b-96f3-5c6949e1f062" />

  berlin:
  <img width="2160" height="1379" alt="image" src="https://github.com/user-attachments/assets/25cf3af8-4884-4076-a58e-f2a716ef3f13" />





  


