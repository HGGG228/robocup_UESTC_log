# robocup_UESTC_log
这里用来存放电子神技的跑图结果与代码修改

# 接下来是在询问学长后对每个部分的讲解
  /Apollo/Apollo2022/adf-sample-agent-java-master 这个路径是官方给出的实例代码，可以不用管
  
  /Apollo/Apollo2022/rcrs-server-master/maps 这个路径下是当前的地图，这里可以确认一下今年的地图是否和往年有所区别
  
  /Apollo/code/UESTC2026_final_pro_yy/UESTC2026/src/main/java/sample_team 这个路径可以不用管，是官方提供的相关内容
  
  /Apollo/code/UESTC2026_final_pro_yy/UESTC2026/src/main/java/UESTC/module/algorithm 这个路劲下为核心的算法（类似于函数包），这个是可以优化的部分

  ATTENTION! 
  
  /Apollo/code/UESTC2026_final_pro_yy/UESTC2026/src/main/java/UESTC/module/complex 这个路径为智能体部分，是要重点优化的部分，里面有五个文件夹，文件夹内的就是不同行为的具体代码，老师们可以丢给ai看看具体每个部分有什么作用，好像一个叫做A*的文件是最重要的，但是由于为了降重改了文件名，可以分别看一下

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


  


