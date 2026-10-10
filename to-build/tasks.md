1. Auto upload to social media icon use this /home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/icon_assets/many social media.jpg


2
Audio source: implement if external mic available, show those in list, user can choose. And in case external mic available, app picks it default before record starting. Because user connect a mic to use it instead of phone's mic.


3
Auto crop settings:
Instead of showing vertical portrait on bracket, show which social media names are suitable this aspect ratio is. Eg 9:16 (YouTube, TikTok...)

4
Background image list remove named 100ydesign , new house furniture, 4787....

User will be able to upload their own background image and those will be displayed in the list, our app provided background images cannot be deleted. User uploaded images can be deleted. max upload 5 images. after that show helpful message 




5. In the screen that shows replacing background, first when cache not available, about 20sec time takes before starting progress, at that moment for some time looks stuck. 
So , invent an algorithm when this extract time is estimated, adjust this with total background remove process. So at very first moment progress bar starts showing 0%. Understand that even if progress bar is not precisely uniform, no problem. But at least user should see always something dynamic on the UI while processing. Example while cropping a rotating emoji is visible.
​
---

Understand my intent. Restate it back to confirm. Flag any gaps or ambiguities. Ask for my confirmation. Once confirmed, proceed with the work. Finish with a git commit.