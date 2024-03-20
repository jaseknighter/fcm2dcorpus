// Engine_FCM2dCorpus

// Inherit methods from CroneEngine
Engine_FCM2dCorpus : CroneEngine {
  var s;
  var twoD_instrument,lua_sender,sc_sender;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

  alloc {
                      
      s = context.server;
      s.options.memSize  = 8192/2; 
      lua_sender = NetAddr.new("127.0.0.1",10111);   
      sc_sender = NetAddr.new("127.0.0.1",57120);   
      lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");
      // point = Buffer.alloc(s,2);
      //////////////
      //init
      //////////////
      OSCFunc.new({ |msg, time, addr, recvPort|
        // var path, file;
        var folder_path="/home/we/dust/code/fcm_2dcorpus/test/";
        // var path="/home/we/dust/audio/fcm/";
        // var folder_path=FluidFilesPath();
        // var folder_path="/home//we/dust/audio/oomph/";

        // var file_path = "/home/we/dust/audio/drums/909_combined.wav";
        // var path=FluidFilesPath();
        "init".postln;
        // path.postln;
        twoD_instrument.(folder_path,nil);
        // twoD_instrument.(nil,file_path);
      }, "/sc_fcm2dcorpus/init");

        Routine.new({
          

          twoD_instrument = {
            arg folder_path, file_path, sliceThresh = 0.1;
            var previous;
            var analyses, normed, umapped, normed_dict, tree;
            var src, play_slice;
            // var src, play_slice, analyses, normed, normed_dict, tree;
            var indices = Buffer(s);
            var loader;
            var point = Buffer.alloc(s,2);

            fork{

              "start loader".postln;
              if (folder_path != nil,{
                folder_path.postln;
                loader = FluidLoadFolder(folder_path).play(s,{
                  "loader loaded".postln;
                  // s.sync;
                  "set mono src buffer".postln;
                  if(loader.buffer.numChannels > 1){
                    "stereo to mono".postln;
                    src = Buffer(s);
                    // FluidBufCompose.process(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                    FluidBufCompose.processBlocking(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                      
                      ("buf composed1").postln;
                      // FluidBufCompose.process(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                      FluidBufCompose.processBlocking(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                        ("buf composed2").postln;
                      });

                    });
                  }{
                    "mono to mono".postln;
                    src = loader.buffer
                  };
                  "sliced".postln;
                });
              },{
                src = Buffer.read(s,file_path);
                s.sync;
                if(src.numChannels > 1){
                  "stereo to mono".postln;
                  // FluidBufCompose.process(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                  FluidBufCompose.processBlocking(s,src,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                    
                    ("buf composed1").postln;
                    // FluidBufCompose.process(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                    FluidBufCompose.processBlocking(s,src,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                      ("buf composed2").postln;
                    });

                  });
                }
              });
              s.sync;
              src.postln;
              // FluidBufOnsetSlice.processBlocking(s,src,metric:9,threshold:sliceThresh,indices:indices,action:{
              FluidBufOnsetSlice.process(s,src,metric:9,threshold:sliceThresh,indices:indices,action:{
                "FluidBufOnsetSlice done".postln;
                "average seconds per slice: %".format(src.duration / indices.numFrames).postln;

                // analysis
                "start analysis".postln;
                analyses = FluidDataSet(s);
                indices.loadToFloatArray(action:{
                  arg fa;
                  // fork{
                    var mfccs = Buffer(s);
                    var stats = Buffer(s);
                    var flat = Buffer(s);

                    // var point = Buffer.alloc(s,2);
                    // weights_buf = Buffer.alloc(s, numFrames: feature_buf.numFrames, numChannels: 1);

                    fa.doAdjacentPairs{
                      arg start, end, i;
                      var num = end - start;

                      FluidBufMFCC.processBlocking(s,src,start,num,features:mfccs,numCoeffs:13,startCoeff:1,action:{});
                      s.sync;
		                  FluidBufStats.processBlocking(s,mfccs,stats:stats,select:[\mean],action:{});
                      s.sync;
		                  FluidBufFlatten.processBlocking(s,stats,destination:flat,action:{});

		                  analyses.addPoint(i,flat);
                      if((i%3) ==2){
                        "slice % / %".format(i,fa.size).postln;
                        s.sync;
                      };
                      s.sync;
                    };

                    s.sync;
                    (">>>>>>>>>>>>analyses done<<<<<<<<<<<").postln;

                    analyses.postln;

                    umapped = FluidDataSet(s);
                    // 5.wait;
                    FluidUMAP(s,numDimensions:2,numNeighbours:15,minDist:0.9,iterations:100).fitTransform(analyses,umapped,action:{
                      "umap done".postln;
                      umapped.print;

                      normed = FluidDataSet(s);
                      FluidNormalize(s).fitTransform(umapped,normed);
                      // s.sync;

                      "normed".postln;
                      normed.postln;

                      tree = FluidKDTree(s, numNeighbours:1, radius:0.5).fit(normed);

                      normed.write(Platform.defaultTempDir+/+"temp_dataset.json");
                      "normed json file generated".postln;
                      [Platform.defaultTempDir+/+"temp_dataset.json"].postln;
                      Routine({
                        
                      1.wait;
                      }).play;
                      // normed_dict.postln;

                      // normed_dict.postln;

                      lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_dumped");
                    });
                    


                    
                  // };
                });

              });
            };

            play_slice = {
              arg index;
              {
                var startsamp = Index.kr(indices,index);
                var stopsamp = Index.kr(indices,index+1);
                var phs = Phasor.ar(0,BufRateScale.ir(src),startsamp,stopsamp);
                var sig = BufRd.ar(1,src,phs);
                var dursecs = (stopsamp - startsamp) / BufSampleRate.ir(src);
                var env;

                dursecs = min(dursecs,1);

                // env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
                env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
                sig.dup * env;
              }.play;
            };

            // play slice
            OSCFunc.new({ |msg, time, addr, recvPort|
              var x=msg[1];
              var y=msg[2];
              point.setn(0,[x,y]);
              tree.kNearest(point,1,{
                arg nearest;
                if(nearest != previous){
                  [x,y,nearest].postln;
                  play_slice.(nearest.asInteger);
                  lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest);
                  previous = nearest;
                }
              });
            }, "/sc_fcm2dcorpus/play_slice");
          };

        }).play;
    
 

  }
  free {
    "free FCM2dCorpus".postln;
    
    twoD_instrument.free;
  }
}

/*
Engine_FCM2dCorpus : CroneEngine {
  var s;
  var twoD_instrument,lua_sender,sc_sender;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

  alloc {
                      
      s = context.server;
      s.options.memSize  = 8192/2; 
      lua_sender = NetAddr.new("127.0.0.1",10111);   
      sc_sender = NetAddr.new("127.0.0.1",57120);   
      lua_sender.sendMsg("/lua_fcm2dcorpus/sc_inited");
      // point = Buffer.alloc(s,2);
      //////////////
      //init
      //////////////
      OSCFunc.new({ |msg, time, addr, recvPort|
        // var path, file;
        var folder_path="/home/we/dust/code/fcm_2dcorpus/test/";
        // var path="/home/we/dust/audio/fcm/";
        // var folder_path=FluidFilesPath();
        // var folder_path="/home//we/dust/audio/oomph/";

        // var file_path = "/home/we/dust/audio/drums/909_combined.wav";
        // var path=FluidFilesPath();
        "init".postln;
        // path.postln;
        twoD_instrument.(folder_path,nil);
        // twoD_instrument.(nil,file_path);
      }, "/sc_fcm2dcorpus/init");

        Routine.new({
          

          twoD_instrument = {
            arg folder_path, file_path, sliceThresh = 0.1;
            var previous;
            var analyses, normed, normed_dict, tree;
            var src, play_slice;
            // var src, play_slice, analyses, normed, normed_dict, tree;
            var indices = Buffer(s);
            var loader;

            fork{

              "start loader".postln;
              if (folder_path != nil,{
                folder_path.postln;
                loader = FluidLoadFolder(folder_path).play(s,{
                  "loader loaded".postln;
                  // s.sync;
                  "set mono src buffer".postln;
                  if(loader.buffer.numChannels > 1){
                    "stereo to mono".postln;
                    src = Buffer(s);
                    // FluidBufCompose.process(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                    FluidBufCompose.processBlocking(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                      
                      ("buf composed1").postln;
                      // FluidBufCompose.process(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                      FluidBufCompose.processBlocking(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                        ("buf composed2").postln;
                      });

                    });
                  }{
                    "mono to mono".postln;
                    src = loader.buffer
                  };
                  "sliced".postln;
                });
              },{
                src = Buffer.read(s,file_path);
                s.sync;
                if(src.numChannels > 1){
                  "stereo to mono".postln;
                  // FluidBufCompose.process(s,loader.buffer,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                  FluidBufCompose.processBlocking(s,src,startChan:0,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,action:{
                    
                    ("buf composed1").postln;
                    // FluidBufCompose.process(s,loader.buffer,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                    FluidBufCompose.processBlocking(s,src,startChan:1,numChans:1,destination:src,destStartChan:0,gain:-6.dbamp,destGain:1,action:{
                      ("buf composed2").postln;
                    });

                  });
                }
              });
              s.sync;
              src.postln;
              // FluidBufOnsetSlice.processBlocking(s,src,metric:9,threshold:sliceThresh,indices:indices,action:{
              FluidBufOnsetSlice.process(s,src,metric:9,threshold:sliceThresh,indices:indices,action:{
                "FluidBufOnsetSlice done".postln;
                "average seconds per slice: %".format(src.duration / indices.numFrames).postln;

                play_slice = {
                  arg index;
                  {
                    var startsamp = Index.kr(indices,index);
                    var stopsamp = Index.kr(indices,index+1);
                    var phs = Phasor.ar(0,BufRateScale.ir(src),startsamp,stopsamp);
                    var sig = BufRd.ar(1,src,phs);
                    var dursecs = (stopsamp - startsamp) / BufSampleRate.ir(src);
                    var env;

                    dursecs = min(dursecs,1);

                    // env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
                    env = EnvGen.kr(Env([0,1,1,0],[0.03,dursecs-0.06,0.03]),doneAction:2);
                    sig.dup * env;
                  }.play;
                };

                // analysis
                "start analysis".postln;
                analyses = FluidDataSet(s);
                indices.loadToFloatArray(action:{
                  arg fa;
                  fork{
                    var spec = Buffer(s);
                    var stats = Buffer(s);
                    var stats2 = Buffer(s);
                    var loudness = Buffer(s);
                    var point = Buffer(s);
                    // weights_buf = Buffer.alloc(s, numFrames: feature_buf.numFrames, numChannels: 1);

                    fa.doAdjacentPairs{
                      arg start, end, i;
                      var num = end - start;

                      FluidBufSpectralShape.processBlocking(s,src,start,num,features:spec,select:[\centroid],action:{
                        FluidBufStats.process(s,spec,stats:stats,select:[\mean],action:{
                          FluidBufLoudness.process(s,src,start,num,features:loudness,select:[\loudness],action:{
                            FluidBufStats.process(s,loudness,stats:stats2,select:[\mean],action:{
                              FluidBufCompose.process(s,stats,destination:point,destStartFrame:0,action:{
                                FluidBufCompose.process(s,stats2,destination:point,destStartFrame:1,action:{
                                  analyses.addPoint(i,point);
                                });
                              });
                            });
                          });
                        });

                      });
                      // FluidBufSpectralShape.processBlocking(s,src,start,num,features:spec,select:[\centroid]);
                      // FluidBufStats.processBlocking(s,spec,stats:stats,select:[\mean]);
                      // FluidBufLoudness.processBlocking(s,src,start,num,features:loudness,select:[\loudness]);
                      // FluidBufStats.processBlocking(s,loudness,stats:stats2,select:[\mean]);
                      // FluidBufCompose.processBlocking(s,stats,destination:point,destStartFrame:0);
                      // FluidBufCompose.processBlocking(s,stats2,destination:point,destStartFrame:1);
                      "slice % / %".format(i,fa.size).postln;
                      s.sync;
                      // if((i%2) ==1){
                      //   "slice % / %".format(i,fa.size).postln;
                      //   s.sync;
                      // };

                    };

                    s.sync;
                    5.wait;
                    (">>>>>>>>>>>>analyses done<<<<<<<<<<<").postln;

                    analyses.postln;
                    normed = FluidDataSet(s);
                    FluidNormalize(s).fitTransform(analyses,normed);
                    // s.sync;

                    "normed".postln;
                    normed.postln;

                    tree = FluidKDTree(s);
                    // s.sync;
                    tree.fit(normed);
                    s.sync;

                    //dump
                    // 5.wait;
                    // "dump".postln;
                    // normed.dump({
                    //   arg dict;
                    //   normed_dict = dict;

                    //   // var point = Buffer.alloc(s,2);
                    //   // var previous = nil;
                    //   // dict.postln;
                    //   // defer{
                    //   //   FluidPlotter(dict:dict,mouseMoveAction:{
                    //   //     arg view, x, y;
                    //   //     [x,y].postln;
                    //   //     point.setn(0,[x,y]);
                    //   //     tree.kNearest(point,1,{
                    //   //       arg nearest;
                    //   //       if(nearest != previous){
                    //   //         nearest.postln;
                    //   //         view.highlight_(nearest);
                    //   //         play_slice.(nearest.asInteger);
                    //   //         previous = nearest;
                    //   //       }
                    //   //     });
                    //   //   });
                    //   // }
                    // });
                    normed.write(Platform.defaultTempDir+/+"temp_dataset.json");
                    s.sync;
                    "normed json file generated".postln;
                    [Platform.defaultTempDir+/+"temp_dataset.json"].postln;
                    // normed_dict.postln;

                    // normed_dict.postln;
                    lua_sender.sendMsg("/lua_fcm2dcorpus/analysis_dumped");

                    // plot
                    OSCFunc.new({ |msg, time, addr, recvPort|
                      var x=msg[1];
                      var y=msg[2];
                      [x,y].postln;
                      point.setn(0,[x,y]);
                      tree.kNearest(point,1,{
                        arg nearest;
                        if(nearest != previous){
                          nearest.postln;
                          play_slice.(nearest.asInteger);
                          lua_sender.sendMsg("/lua_fcm2dcorpus/slice_played",nearest);
                          previous = nearest;
                        }
                      });

                    }, "/sc_fcm2dcorpus/play_slice");
                  };
                });

              });
            


            }
          };
        }).play;
    
 

  }
  free {
    "free FCM2dCorpus".postln;
    
    twoD_instrument.free;
  }
}
*/